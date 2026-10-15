package edu.campusbite.dao;

import edu.campusbite.model.FoodItem;
import edu.campusbite.util.CsvDataStore;
import edu.campusbite.util.CsvDataStore.Record;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class FoodDao {
    public List<FoodItem> list(String search, String category, String sort) throws IOException {
        String query = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        String selectedCategory = category == null ? "" : category.trim();
        return CsvDataStore.read(state -> {
            List<Record> foods = new ArrayList<>(state.all("food"));
            foods.removeIf(food -> (!query.isEmpty()
                    && !food.get("name").toLowerCase(Locale.ROOT).contains(query)
                    && !food.get("description").toLowerCase(Locale.ROOT).contains(query))
                    || (!selectedCategory.isEmpty() && !"All".equalsIgnoreCase(selectedCategory)
                    && !selectedCategory.equalsIgnoreCase(food.get("category"))));
            Comparator<Record> comparator = switch (sort == null ? "" : sort) {
                case "price-asc" -> Comparator.comparing(food -> food.decimalValue("price"));
                case "price-desc" -> Comparator.comparing((Record food) -> food.decimalValue("price")).reversed();
                case "name" -> Comparator.comparing(food -> food.get("name"), String.CASE_INSENSITIVE_ORDER);
                default -> Comparator.comparing((Record food) -> food.get("category"), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(food -> food.get("name"), String.CASE_INSENSITIVE_ORDER);
            };
            foods.sort(comparator.thenComparing(food -> food.get("name"), String.CASE_INSENSITIVE_ORDER));
            return foods.stream().map(FoodDao::toModel).toList();
        });
    }

    public long save(Long id, String name, String description, String category, BigDecimal price,
                     int stock, String imageUrl, boolean available) throws IOException {
        return CsvDataStore.update(state -> {
            Record food = id == null ? null : state.find("food", id);
            if (id != null && food == null) {
                throw new IllegalArgumentException("Menu item not found.");
            }
            long existingId = food == null ? 0 : food.longValue("id");
            if (state.all("food").stream().anyMatch(row -> row.longValue("id") != existingId
                    && row.get("name").equalsIgnoreCase(name))) {
                throw new CsvDataStore.ConflictException("A menu item with this name already exists.");
            }
            if (food == null) {
                food = state.create("food");
                state.add(food);
            }
            food.set("name", name);
            food.set("description", description);
            food.set("category", category);
            food.set("price", price.toPlainString());
            food.set("stockQuantity", Integer.toString(stock));
            food.set("imageUrl", imageUrl);
            food.set("available", Boolean.toString(available));
            return food.longValue("id");
        });
    }

    public void delete(long id) throws IOException {
        CsvDataStore.update(state -> {
            Record food = state.find("food", id);
            if (food == null) {
                throw new IllegalArgumentException("Menu item not found.");
            }
            if (state.all("orderItem").stream().anyMatch(item -> item.longValue("foodItemId") == id)) {
                throw new CsvDataStore.ConflictException("This item is part of an order and cannot be deleted.");
            }
            state.remove(food);
            return null;
        });
    }

    public void updateStockAndAvailability(long id, int stock, boolean available) throws IOException {
        if (stock < 0) {
            throw new IllegalArgumentException("Stock cannot be negative.");
        }
        CsvDataStore.update(state -> {
            Record food = state.find("food", id);
            if (food == null) {
                throw new IllegalArgumentException("Menu item not found.");
            }
            food.set("stockQuantity", Integer.toString(stock));
            food.set("available", Boolean.toString(available));
            return null;
        });
    }

    private static FoodItem toModel(Record food) {
        return new FoodItem(food.longValue("id"), food.get("name"), food.get("description"),
                food.get("category"), food.decimalValue("price"), food.intValue("stockQuantity"),
                food.get("imageUrl"), food.booleanValue("available"));
    }
}
