package it.unimib.milanrailsim.results;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unit costs per category, loaded from a JSON file (config/costs.json).
 * Every entry carries its unit and source; a zero cost means "not yet
 * estimated" and is loadable but refused by the cost model.
 */
public final class CostParameters {

	public record Entry(double unitCost, String unit, String source) {

		public boolean isSet() {
			return unitCost > 0;
		}
	}

	private final String currency;
	private final Map<String, Entry> categories;

	private CostParameters(String currency, Map<String, Entry> categories) {
		this.currency = currency;
		this.categories = categories;
	}

	public static CostParameters load(Path json) {
		JsonNode root;
		try {
			root = new ObjectMapper().readTree(json.toFile());
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read cost parameters: " + json, e);
		}
		String currency = requireText(root, "currency", json);
		JsonNode categoriesNode = root.get("categories");
		if (categoriesNode == null || !categoriesNode.isObject()) {
			throw new IllegalArgumentException("Missing 'categories' object in " + json);
		}
		Map<String, Entry> categories = new LinkedHashMap<>();
		categoriesNode.properties().forEach(field -> {
			JsonNode node = field.getValue();
			Entry entry = new Entry(node.path("unitCost").asDouble(-1),
				requireText(node, "unit", json), requireText(node, "source", json));
			if (entry.unitCost() < 0) {
				throw new IllegalArgumentException(
					"Negative or missing unitCost for '" + field.getKey() + "' in " + json);
			}
			categories.put(field.getKey(), entry);
		});
		return new CostParameters(currency, categories);
	}

	private static String requireText(JsonNode node, String field, Path json) {
		String value = node.path(field).asText("");
		if (value.isBlank()) {
			throw new IllegalArgumentException("Missing or blank '" + field + "' in " + json);
		}
		return value;
	}

	public String currency() {
		return currency;
	}

	public Entry category(String name) {
		Entry entry = categories.get(name);
		if (entry == null) {
			throw new IllegalArgumentException("Unknown cost category: " + name);
		}
		return entry;
	}

	public Map<String, Entry> categories() {
		return Map.copyOf(categories);
	}
}
