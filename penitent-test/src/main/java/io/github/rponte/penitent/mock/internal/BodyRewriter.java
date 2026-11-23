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
import ch.islandsql.grammar.IslandSqlParser.PlsqlFunctionSourceContext;
import ch.islandsql.grammar.IslandSqlParser.PlsqlProcedureSourceContext;
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
			Subprogram other = found.put(definitionOf(document, qualifiedPackage, subprogram.routine()), subprogram);
			if (other != null) {
				// each interface binds its own parameters, and the calls are recorded by them: one list per routine
				throw new IllegalStateException(subprogram.routine().qualifiedName() + " is stubbed in this test"
						+ " through interfaces that bind it differently, " + other.routine().parameters().stream()
								.map(RoutineMetadata.Argument::name).toList()
						+ " and " + subprogram.routine().parameters().stream().map(RoutineMetadata.Argument::name)
								.toList()
						+ ", by position and by name or in another order: stub a routine through one interface in"
						+ " a test");
			}
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
		// a CLOB, which no argument overflows: a long text or a LOB is recorded whole
		StringBuilder code = new StringBuilder("\n    l_penitent_args CLOB;\n");
		// a BOOLEAN goes into SQL only from Oracle 23ai on: before JSON_ARRAY, PL/SQL writes it as 'true' or 'false'
		for (int i = 0; i < recorded.size(); i++) {
			if (recorded.get(i).isBoolean()) {
				code.append("    l_penitent_flag_").append(i + 1).append(" VARCHAR2(5) := CASE WHEN ")
						.append(recorded.get(i).name()).append(" THEN 'true' WHEN NOT ").append(recorded.get(i).name())
						.append(" THEN 'false' END;\n");
			}
		}
		code.append("  BEGIN\n");
		if (recorded.isEmpty()) {
			code.append("    l_penitent_args := '[]';\n");
		} else {
			StringJoiner arguments = new StringJoiner(", ", "    SELECT JSON_ARRAY(", " NULL ON NULL RETURNING CLOB) INTO"
					+ " l_penitent_args FROM DUAL;\n");
			// JSON_ARRAY takes scalars: an object type, a collection or a record is marked, keeping each one's position
			for (int i = 0; i < recorded.size(); i++) {
				RoutineMetadata.Argument argument = recorded.get(i);
				arguments.add(composite(argument) ? "'" + NOT_RECORDED + "'"
						: argument.isBoolean() ? "l_penitent_flag_" + (i + 1)
						: argument.isJson() ? recordedJson(argument.name()) : argument.name());
			}
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
				if (matcher instanceof Matcher.InOut inOut) {
					// an IN OUT: the condition on what came in, as for an IN parameter, and what goes out, set as an
					// OUT's. The condition is tested before any stub sets anything
					outs.append(parameter.name()).append(" := ").append(written(parameter, inOut.goesOut()))
							.append("; ");
					matcher = new Matcher.Equal(inOut.cameIn());
				}
				if (matcher instanceof Matcher.Out out) {
					outs.append(parameter.name()).append(" := ").append(written(parameter, out.value())).append("; ");
				} else {
					boolean byValue = matcher instanceof Matcher.Equal equal && equal.value() != null;
					// a LOB has no =: DBMS_LOB.COMPARE, which gives NULL when either side is, as = would. Nor has JSON:
					// JSON_EQUAL, which gives NULL for a NULL argument too
					String check = byValue && parameter.isJson()
							? "JSON_EQUAL(" + parameter.name() + ", " + written(parameter, ((Matcher.Equal) matcher).value())
									+ ")"
							: byValue && lob(parameter)
									? "DBMS_LOB.COMPARE(" + parameter.name() + ", "
											+ PlsqlLiterals.of(((Matcher.Equal) matcher).value()) + ") = 0"
									: matcher.condition(parameter.name());
					if (check != null) {
						condition.add(check);
					}
				}
			}
			code.append("    IF ").append(condition.length() == 0 ? "TRUE" : condition).append(" THEN\n      ")
					.append(outs).append(action(stub.action(), routine)).append("\n    END IF;\n");
		}
		// the start of the arguments, and their length when there is more: a message holds 2,048 bytes
		code.append("    RAISE_APPLICATION_ERROR(-20999, '").append(qualified)
				.append(": no stub matches the arguments ' || DBMS_LOB.SUBSTR(l_penitent_args, 1500, 1)")
				.append(" || CASE WHEN DBMS_LOB.GETLENGTH(l_penitent_args) > 1500 THEN '... (' ||")
				.append(" DBMS_LOB.GETLENGTH(l_penitent_args) || ' characters)' END);\n  END ").append(name);
		return code.toString();
	}

	private static String action(Stub.Action action, RoutineMetadata routine) {
		if (action instanceof Stub.Return returned) {
			return returned.function() ? "RETURN " + written(routine.returnValue().orElseThrow(), returned.value()) + ";"
					: "RETURN;";
		}
		if (action instanceof Stub.ApplicationError error) {
			return "RAISE_APPLICATION_ERROR(" + error.code() + ", " + PlsqlLiterals.of(error.message()) + ");";
		}
		return "RAISE " + ((Stub.Raise) action).name() + ";";
	}

	/**
	 * A value as PL/SQL writes it for {@code argument}: for Oracle's JSON type, its text, already checked by the core, as
	 * {@code JSON('...')}, which a long text's {@code TO_CLOB} pieces fit too; {@code NULL} as SQL {@code NULL}. Without
	 * {@code JSON(...)}, a text literal is of the wrong type for a JSON parameter, and the body does not compile.
	 */
	static String written(RoutineMetadata.Argument argument, Object value) {
		String literal = PlsqlLiterals.of(value);
		return argument.isJson() && value != null ? "JSON(" + literal + ")" : literal;
	}

	/**
	 * A JSON argument as a call records it: {@code {"v": document}}, and {@code {}} for SQL {@code NULL}, since
	 * {@code ABSENT ON NULL} leaves the member out. In {@code JSON_ARRAY}, a SQL {@code NULL} and the JSON {@code null}
	 * would both be {@code null}; recorded so, {@code verify} tells them apart with {@code JSON_EXISTS}, and compares
	 * objects with objects, which 21c needs for a scalar at the top. Measured on 23ai and 21c (#133).
	 */
	static String recordedJson(String name) {
		return "JSON_OBJECT('v' VALUE " + name + " ABSENT ON NULL)";
	}

	/** A {@code CLOB}, an {@code NCLOB} or a {@code BLOB}, which PL/SQL does not compare with {@code =}. */
	static boolean lob(RoutineMetadata.Argument parameter) {
		return parameter.dataType().endsWith("LOB");
	}

	/** How a call of {@code routine} is recorded: its name and its parameters, which tell overloads apart. */
	static String callKey(RoutineMetadata routine) {
		StringJoiner key = new StringJoiner(",", routine.qualifiedName() + "(", ")");
		routine.parameters().forEach(parameter -> key.add(parameter.name()));
		return key.toString();
	}

	/**
	 * The one definition of {@code routine}: same name, and each of its parameters at the position the dictionary gives
	 * it. Bound by name, the routine has its parameters in the order of the Java method, and only those the method
	 * declares, a parameter with a {@code DEFAULT} left out; so they are found by position, not compared as a list.
	 */
	private static ParserRuleContext definitionOf(IslandSqlDocument document, String qualifiedPackage,
			RoutineMetadata routine) {
		List<String> wanted = routine.parameters().stream().map(RoutineMetadata.Argument::name).toList();
		List<ParserRuleContext> definitions = new ArrayList<>();
		definitions.addAll(document.getAllContentsOfType(FunctionDefinitionContext.class));
		definitions.addAll(document.getAllContentsOfType(ProcedureDefinitionContext.class));
		// a standalone routine is a CREATE of its own, whose source has a declare section and a body as well
		definitions.addAll(document.getAllContentsOfType(PlsqlFunctionSourceContext.class));
		definitions.addAll(document.getAllContentsOfType(PlsqlProcedureSourceContext.class));
		List<ParserRuleContext> matching = definitions.stream()
				.filter(definition -> identifier(nameOf(definition)).equals(routine.routineName()))
				.filter(definition -> declares(definition, routine))
				.toList();
		if (matching.size() != 1) {
			throw new IllegalStateException(matching.size() + " definitions of " + routine.routineName() + wanted
					+ " were found in the body of " + qualifiedPackage + ", where exactly one was expected");
		}
		return matching.get(0);
	}

	/** Whether {@code definition} has each parameter of {@code routine} at its position, and no more without them. */
	private static boolean declares(ParserRuleContext definition, RoutineMetadata routine) {
		List<String> declared = parametersOf(definition).stream()
				.map(parameter -> identifier(parameter.parameter.getText())).toList();
		if (!routine.byName()) {
			return declared.equals(routine.parameters().stream().map(RoutineMetadata.Argument::name).toList());
		}
		for (RoutineMetadata.Argument parameter : routine.parameters()) {
			int index = parameter.position() - 1;
			if (index < 0 || index >= declared.size() || !declared.get(index).equals(parameter.name())) {
				return false;
			}
		}
		return true;
	}

	private static String identifier(String text) {
		return text.startsWith("\"") ? RoutineMetadata.normalize(text) : text.toUpperCase(Locale.ROOT);
	}

	private static String nameOf(ParserRuleContext definition) {
		if (definition instanceof FunctionDefinitionContext function) {
			return function.functionHeading().functionName.getText();
		}
		if (definition instanceof ProcedureDefinitionContext procedure) {
			return procedure.procedureHeading().procedure.getText();
		}
		return definition instanceof PlsqlFunctionSourceContext function ? function.functionName.getText()
				: ((PlsqlProcedureSourceContext) definition).procedureName.getText();
	}

	private static List<ParameterDeclarationContext> parametersOf(ParserRuleContext definition) {
		if (definition instanceof FunctionDefinitionContext function) {
			return function.functionHeading().parameterDeclaration();
		}
		if (definition instanceof ProcedureDefinitionContext procedure) {
			return procedure.procedureHeading().parameterDeclaration();
		}
		return definition instanceof PlsqlFunctionSourceContext function ? function.parameterDeclaration()
				: ((PlsqlProcedureSourceContext) definition).parameterDeclaration();
	}

	private static ParserRuleContext declareSectionOf(ParserRuleContext definition) {
		if (definition instanceof FunctionDefinitionContext function) {
			return function.declareSection();
		}
		if (definition instanceof ProcedureDefinitionContext procedure) {
			return procedure.declareSection();
		}
		return definition instanceof PlsqlFunctionSourceContext function ? function.declareSection()
				: ((PlsqlProcedureSourceContext) definition).declareSection();
	}

	private static ParserRuleContext bodyOf(ParserRuleContext definition) {
		if (definition instanceof FunctionDefinitionContext function) {
			return function.body();
		}
		if (definition instanceof ProcedureDefinitionContext procedure) {
			return procedure.body();
		}
		return definition instanceof PlsqlFunctionSourceContext function ? function.body()
				: ((PlsqlProcedureSourceContext) definition).body();
	}
}
