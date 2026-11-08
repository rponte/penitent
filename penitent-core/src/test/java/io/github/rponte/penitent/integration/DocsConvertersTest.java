package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlBoolean;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.convert.PlsqlConvert;
import io.github.rponte.penitent.convert.PlsqlConverter;
import io.github.rponte.penitent.convert.PlsqlConverters;
import io.github.rponte.penitent.convert.PlsqlEnumCodeConverter;
import io.github.rponte.penitent.exception.PlsqlMappingException;

/**
 * The examples of docs/converters.md. The regions are what the page shows, and
 * {@link DocsPagesTest} checks that the page still shows them.
 */
class DocsConvertersTest extends OracleIntegrationTest {

	// snippet: converters/value-object
	public record TaxId(String value) {
	}

	@PlsqlPackage("CUSTOMERS_PKG")
	public interface Customers {

	    Long findByTaxId(TaxId taxId);
	}
	// end-snippet

	// snippet: converters/tier
	public enum Tier {
	    GOLD("G"), SILVER("S");

	    private final String code;

	    Tier(String code) {
	        this.code = code;
	    }

	    public String code() {
	        return code;
	    }
	}
	// end-snippet

	@PlsqlPackage("CUSTOMERS_PKG")
	interface CustomerRecords {

		// snippet: converters/rowtype
		public record Customer(Long id, String name, TaxId taxId, Tier tier,
		                       @PlsqlBoolean(trueValue = "S", falseValue = "N") boolean vip,
		                       LocalDate bornOn, LocalDateTime createdAt) {
		}

		Customer findCustomer(long id);   // customers.findCustomer(1).taxId() is TaxId[value=12345678901]
		// end-snippet

		// snippet: converters/tier-methods
		Tier tierOf(long id);         // customers.tierOf(1) is GOLD
		int countByTier(Tier tier);   // customers.countByTier(Tier.SILVER) sends 'S'
		// end-snippet
	}

	@PlsqlPackage("CUSTOMERS_PKG")
	interface CustomerLookup {

		// snippet: converters/annotation
		public class TaxIdConverter implements PlsqlConverter<TaxId, String> {

		    @Override
		    public String toDatabase(TaxId taxId) {
		        return taxId == null ? null : taxId.value();
		    }

		    @Override
		    public TaxId fromDatabase(String value) {
		        return value == null ? null : new TaxId(value);
		    }
		}

		Long findByTaxId(@PlsqlConvert(TaxIdConverter.class) TaxId taxId);
		// end-snippet
	}

	/** A class nested in an interface is static, as the annotation needs, like the page's top-level one. */
	interface TierConverters {

		// snippet: converters/tier-converter
		public class TierConverter extends PlsqlEnumCodeConverter<Tier, String> {
		    public TierConverter() {
		        super(Tier.class, String.class, Tier::code);
		    }
		}
		// end-snippet
	}

	@PlsqlPackage("CUSTOMERS_PKG")
	interface TierByAnnotation {
		@PlsqlConvert(TierConverters.TierConverter.class)
		Tier tierOf(long id);
	}

	private final DataSource dataSource = DATA_SOURCE;

	@Test
	@DisplayName("a value object, with a registry")
	void value_object() {
		// snippet: converters/registry
		PlsqlConverters converters = PlsqlConverters.of(
		        PlsqlConverter.ofNullable(TaxId.class, String.class, TaxId::value, TaxId::new));

		Customers customers = PlsqlPackages.create(Customers.class, dataSource, converters);

		customers.findByTaxId(new TaxId("12345678901"));   // 1
		// end-snippet

		assertThat(customers.findByTaxId(new TaxId("12345678901"))).isEqualTo(1L);
	}

	@Test
	@DisplayName("an enum by code, in parameters, results and attributes")
	void enum_by_code() {
		// snippet: converters/registry-with-tier
		PlsqlConverters converters = PlsqlConverters.of(
		        PlsqlConverter.ofNullable(TaxId.class, String.class, TaxId::value, TaxId::new),
		        PlsqlConverter.enumByCode(Tier.class, String.class, Tier::code));
		// end-snippet

		CustomerRecords customers = PlsqlPackages.create(CustomerRecords.class, dataSource, converters);

		assertThat(customers.tierOf(1)).isEqualTo(Tier.GOLD);
		assertThat(customers.countByTier(Tier.SILVER)).isEqualTo(1);
		assertThat(customers.findCustomer(1).taxId()).hasToString("TaxId[value=12345678901]");
		assertThat(customers.findCustomer(1).tier()).isEqualTo(Tier.GOLD);
		assertThat(customers.findCustomer(2).taxId()).isNull();
	}

	@Test
	@DisplayName("@PlsqlConvert at a single point")
	void annotation() {
		CustomerLookup lookup = PlsqlPackages.create(CustomerLookup.class, dataSource);
		assertThat(lookup.findByTaxId(new TaxId("12345678901"))).isEqualTo(1L);

		TierByAnnotation tiers = PlsqlPackages.create(TierByAnnotation.class, dataSource);
		assertThat(tiers.tierOf(1)).isEqualTo(Tier.GOLD);
	}

	@Test
	@DisplayName("the message shown on the page")
	void converter_null() {
		Customers strict = PlsqlPackages.create(Customers.class, dataSource, PlsqlConverters.of(
				PlsqlConverter.of(TaxId.class, String.class, TaxId::value, TaxId::new)));

		assertThatThrownBy(() -> strict.findByTaxId(null))
				.isInstanceOf(PlsqlMappingException.class)
				.satisfies(e -> Docs.assertPagesShow(Docs.asDocumented(e.getMessage(), getClass()),
						"converters.md#converter-null"));
	}
}
