package io.github.rponte.penitent.mock.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

import org.antlr.v4.runtime.ParserRuleContext;

import ch.islandsql.grammar.IslandSqlDocument;
import ch.islandsql.grammar.IslandSqlParser.FunctionDefinitionContext;
import ch.islandsql.grammar.IslandSqlParser.ParameterDeclarationContext;
import ch.islandsql.grammar.IslandSqlParser.ProcedureDefinitionContext;

import io.github.rponte.penitent.internal.metadata.RoutineMetadata;

/**
 * A package body, as the database has it, with the stubbed subprograms rewritten. Only the declare section and the
 * body of each stubbed subprogram change: its heading, and so {@code %TYPE}, {@code SUBTYPE} and {@code DEFAULT},
 * and every other subprogram, private ones included, stay as written.
 */
final class BodyRewriter {

	/** A stubbed subprogram: the routine it is, and its stubs, the last one winning, as in Mockito. */
	record Subprogram(RoutineMetadata routine, List<StubbedCall> stubs) {
	}

	/** A stub, with each parameter of the routine matched to the Java argument that binds to it. */
	record StubbedCall(Map<String, Matcher> byParameter, Stub.Action action) {
	}

	/** What a recorded call holds for an argument of an object type, a collection or a record. */
	static final String NOT_RECORDED = "(not recorded)";

	private BodyRewriter() {
	}

	/** An object type, a collection, a record or a cursor: no PL/SQL literal stands for it, nor JSON_ARRAY takes it. */
	static boolean composite(RoutineMetadata.Argument argument) {
		return argument.isStruct() || argument.isCollection() || argument.isCursor();
	}

	static String rewrite(String qualifiedPackage, String body, List<Subprogram> subprograms) {
		return rewrite(qualifiedPackage, body, subprograms, new LinkedHashMap<>());
	}

	/** As {@link #rewrite}, also putting each generated subprogram, whole, under its {@link #callKey}. */
	static String rewrite(String qualifiedPackage, String body, List<Subprogram> subprograms,
			Map<String, String> generatedByCall) {
		IslandSqlDocument document = IslandSqlDocument.parse(body);
		if (!document.getSyntaxErrors().isEmpty()) {
			throw new IllegalStateException("the body of " + qualifiedPackage + " could not be parsed, so it cannot be"
					+ " mocked: " + document.getSyntaxErrors());
		}
		Map<ParserRuleContext, Subprogram> found = new LinkedHashMap<>();
		for (Subprogram subprogram : subprograms) {
			found.put(definitionOf(document, qualifiedPackage, subprogram.routine()), subprogram);
		}
		// from the end of the text backwards, so that each replacement leaves the positions before it valid
		List<ParserRuleContext> definitions = new ArrayList<>(found.keySet());
		definitions.sort(Comparator.comparingInt((ParserRuleContext d) -> d.getStart().getStartIndex()).reversed());
		StringBuilder text = new StringBuilder(body);
		for (ParserRuleContext definition : definitions) {
			ParserRuleContext from = declareSectionOf(definition) != null ? declareSectionOf(definition)
					: bodyOf(definition);
			int start = from.getStart().getStartIndex();
			// from right after IS or AS: the generated code brings its own line breaks and indentation
			while (start > 0 && Character.isWhitespace(body.charAt(start - 1))) {
				start--;
			}
			int stop = bodyOf(definition).getStop().getStopIndex() + 1;
			String generated = generate(found.get(definition), nameOf(definition));
			// the body of a subprogram, in IslandSQL, ends with its semicolon
			String replacement = body.substring(start, stop).stripTrailing().endsWith(";") ? generated + ";" : generated;
			text.replace(start, stop, replacement);
			generatedByCall.put(callKey(found.get(definition).routine()),
					body.substring(definition.getStart().getStartIndex(), start) + replacement);
		}
		return text.toString();
	}

	private static String generate(Subprogram subprogram, String name) {
		RoutineMetadata routine = subprogram.routine();
		String qualified = routine.qualifiedName();
		List<RoutineMetadata.Argument> recorded = routine.parameters().stream()
				.filter(RoutineMetadata.Argument::isIn).toList();
		StringBuilder code = new StringBuilder("\n    l_penitent_args VARCHAR2(4000);\n  BEGIN\n");
		if (recorded.isEmpty()) {
			code.append("    l_penitent_args := '[]';\n");
		} else {
			StringJoiner arguments = new StringJoiner(", ", "    SELECT JSON_ARRAY(", " NULL ON NULL) INTO l_penitent_args"
					+ " FROM DUAL;\n");
			// JSON_ARRAY takes scalars: an object type, a collection or a record is marked, keeping each one's position
			recorded.forEach(argument -> arguments.add(composite(argument) ? "'" + NOT_RECORDED + "'" : argument.name()));
			code.append(arguments);
		}
		code.append("    PENITENT_MOCK.record('").append(callKey(routine)).append("', l_penitent_args);\n");
		List<StubbedCall> stubs = new ArrayList<>(subprogram.stubs());
		java.util.Collections.reverse(stubs);
		for (StubbedCall stub : stubs) {
			StringJoiner condition = new StringJoiner(" AND ");
			StringBuilder outs = new StringBuilder();
			for (RoutineMetadata.Argument parameter : routine.parameters()) {
				Matcher matcher = stub.byParameter().get(parameter.name());
				if (matcher == null) {
					continue;
				}
				if (matcher instanceof Matcher.Out out) {
					outs.append(parameter.name()).append(" := ").append(PlsqlLiterals.of(out.value())).append("; ");
				} else {
					String check = matcher.condition(parameter.name());
					if (check != null) {
						condition.add(check);
					}
				}
			}
			code.append("    IF ").append(condition.length() == 0 ? "TRUE" : condition).append(" THEN\n      ")
					.append(outs).append(action(stub.action())).append("\n    END IF;\n");
		}
		code.append("    RAISE_APPLICATION_ERROR(-20999, '").append(qualified)
				.append(": no stub matches the arguments ' || l_penitent_args);\n  END ").append(name);
		return code.toString();
	}

	private static String action(Stub.Action action) {
		if (action instanceof Stub.Return returned) {
			return returned.function() ? "RETURN " + PlsqlLiterals.of(returned.value()) + ";" : "RETURN;";
		}
		if (action instanceof Stub.ApplicationError error) {
			return "RAISE_APPLICATION_ERROR(" + error.code() + ", " + PlsqlLiterals.of(error.message()) + ");";
		}
		return "RAISE " + ((Stub.Predefined) action).name() + ";";
	}

	/** How a call of {@code routine} is recorded: its name and its parameters, which tell overloads apart. */
	static String callKey(RoutineMetadata routine) {
		StringJoiner key = new StringJoiner(",", routine.qualifiedName() + "(", ")");
		routine.parameters().forEach(parameter -> key.add(parameter.name()));
		return key.toString();
	}

	/** The one definition of {@code routine}: same name, same parameters, in the same order. */
	private static ParserRuleContext definitionOf(IslandSqlDocument document, String qualifiedPackage,
			RoutineMetadata routine) {
		List<String> wanted = routine.parameters().stream().map(RoutineMetadata.Argument::name).toList();
		List<ParserRuleContext> definitions = new ArrayList<>();
		definitions.addAll(document.getAllContentsOfType(FunctionDefinitionContext.class));
		definitions.addAll(document.getAllContentsOfType(ProcedureDefinitionContext.class));
		List<ParserRuleContext> matching = definitions.stream()
				.filter(definition -> identifier(nameOf(definition)).equals(routine.routineName()))
				.filter(definition -> parametersOf(definition).stream()
						.map(parameter -> identifier(parameter.parameter.getText())).toList().equals(wanted))
				.toList();
		if (matching.size() != 1) {
			throw new IllegalStateException(matching.size() + " definitions of " + routine.routineName() + wanted
					+ " were found in the body of " + qualifiedPackage + ", where exactly one was expected");
		}
		return matching.get(0);
	}

	private static String identifier(String text) {
		return text.startsWith("\"") ? RoutineMetadata.normalize(text) : text.toUpperCase(Locale.ROOT);
	}

	private static String nameOf(ParserRuleContext definition) {
		return definition instanceof FunctionDefinitionContext function
				? function.functionHeading().functionName.getText()
				: ((ProcedureDefinitionContext) definition).procedureHeading().procedure.getText();
	}

	private static List<ParameterDeclarationContext> parametersOf(ParserRuleContext definition) {
		return definition instanceof FunctionDefinitionContext function
				? function.functionHeading().parameterDeclaration()
				: ((ProcedureDefinitionContext) definition).procedureHeading().parameterDeclaration();
	}

	private static ParserRuleContext declareSectionOf(ParserRuleContext definition) {
		return definition instanceof FunctionDefinitionContext function ? function.declareSection()
				: ((ProcedureDefinitionContext) definition).declareSection();
	}

	private static ParserRuleContext bodyOf(ParserRuleContext definition) {
		return definition instanceof FunctionDefinitionContext function ? function.body()
				: ((ProcedureDefinitionContext) definition).body();
	}
}
