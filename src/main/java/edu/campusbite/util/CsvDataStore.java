package edu.campusbite.util;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class CsvDataStore {
    private static final String[] COLUMNS = {
        "type", "id", "userId", "orderId", "foodItemId", "slotId", "name", "email", "passwordHash",
        "role", "active", "createdAt", "description", "category", "price", "stockQuantity",
        "imageUrl", "available", "slotDate", "startTime", "endTime", "capacity",
        "reservedCount", "tokenNumber", "totalAmount", "status", "paymentMethod",
        "paymentStatus", "idempotencyKey", "updatedAt", "readyAt", "cancellationReason",
        "itemNameSnapshot", "unitPrice", "quantity", "subtotal", "message", "isRead",
        "settingKey", "settingValue"
    };
    private static final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock();
    private static final Path FILE = resolveFile();

    private CsvDataStore() {
    }

    public static <T> T read(Reader<T> action) throws IOException {
        initializeIfMissing();
        LOCK.readLock().lock();
        try {
            return action.apply(load());
        } catch (ConflictException exception) {
            throw exception;
        } catch (IOException exception) {
            throw storageError(exception);
        } finally {
            LOCK.readLock().unlock();
        }
    }

    public static <T> T update(Writer<T> action) throws IOException {
        LOCK.writeLock().lock();
        try {
            State state = Files.exists(FILE) ? load() : seededState();
            State working = new State(state.records());
            T result = action.apply(working);
            save(working);
            return result;
        } catch (ConflictException exception) {
            throw exception;
        } catch (IOException exception) {
            throw storageError(exception);
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    public static Path file() {
        return FILE;
    }

    public static String bootstrapAdminEmail() {
        String email = System.getenv("ADMIN_EMAIL");
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static void initializeIfMissing() throws IOException {
        if (Files.exists(FILE)) {
            return;
        }
        LOCK.writeLock().lock();
        try {
            if (!Files.exists(FILE)) {
                save(seededState());
            }
        } catch (IOException exception) {
            throw storageError(exception);
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    private static Path resolveFile() {
        String configured = System.getProperty("campusbite.dataFile");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("CAMPUSBITE_DATA_FILE");
        }
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home"), ".campusbite", "campusbite.csv")
                .toAbsolutePath().normalize();
    }

    private static State load() throws IOException {
        List<Record> records = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            List<String> header = readCsvRow(reader);
            List<String> legacyColumns = new ArrayList<>(List.of(COLUMNS));
            legacyColumns.remove("orderId");
            if (header == null || (!header.equals(List.of(COLUMNS)) && !header.equals(legacyColumns))) {
                throw new IOException("The CampusBite CSV header is invalid: " + FILE);
            }
            List<String> values;
            while ((values = readCsvRow(reader)) != null) {
                if (values.size() != header.size()) {
                    throw new IOException("A row in the CampusBite CSV has an invalid number of columns.");
                }
                Record record = new Record();
                for (int i = 0; i < header.size(); i++) {
                    record.set(header.get(i), values.get(i));
                }
                if (header.equals(legacyColumns)) {
                    if ("orderItem".equals(record.get("type"))) record.set("orderId", record.get("userId"));
                    if ("notification".equals(record.get("type"))) record.set("orderId", record.get("slotId"));
                }
                records.add(record);
            }
        }
        return new State(records);
    }

    private static void save(State state) throws IOException {
        Files.createDirectories(FILE.getParent());
        Path temporary = FILE.resolveSibling(FILE.getFileName() + ".tmp");
        List<Record> records = new ArrayList<>(state.records());
        records.sort(Comparator.comparing((Record record) -> record.get("type"))
                .thenComparingLong(record -> record.longValue("id")));
        try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            writeCsvRow(writer, List.of(COLUMNS));
            for (Record record : records) {
                List<String> row = new ArrayList<>(COLUMNS.length);
                for (String column : COLUMNS) {
                    row.add(record.get(column));
                }
                writeCsvRow(writer, row);
            }
        }
        try {
            Files.move(temporary, FILE, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, FILE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static List<String> readCsvRow(BufferedReader reader) throws IOException {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean started = false;
        int current;
        while ((current = reader.read()) != -1) {
            char character = (char) current;
            started = true;
            if (quoted) {
                if (character == '"') {
                    reader.mark(1);
                    int next = reader.read();
                    if (next == '"') {
                        field.append('"');
                    } else {
                        quoted = false;
                        if (next != -1) {
                            reader.reset();
                        }
                    }
                } else {
                    field.append(character);
                }
            } else if (character == ',' ) {
                fields.add(field.toString());
                field.setLength(0);
            } else if (character == '"') {
                if (field.length() != 0) {
                    throw new IOException("Invalid quote in CSV field.");
                }
                quoted = true;
            } else if (character == '\n' || character == '\r') {
                if (character == '\r') {
                    reader.mark(1);
                    int next = reader.read();
                    if (next != '\n' && next != -1) {
                        reader.reset();
                    }
                }
                fields.add(field.toString());
                return fields;
            } else {
                field.append(character);
            }
        }
        if (quoted) {
            throw new IOException("Unterminated quoted field in CSV.");
        }
        if (!started && fields.isEmpty() && field.isEmpty()) {
            return null;
        }
        fields.add(field.toString());
        return fields;
    }

    private static void writeCsvRow(BufferedWriter writer, List<String> values) throws IOException {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                writer.write(',');
            }
            String value = values.get(i) == null ? "" : values.get(i);
            boolean quote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                    || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
            if (quote) {
                writer.write('"');
            }
            writer.write(value.replace("\"", "\"\""));
            if (quote) {
                writer.write('"');
            }
        }
        writer.newLine();
    }

    private static State seededState() {
        State state = new State(new ArrayList<>());
        setting(state, "slot_duration_minutes", "5");
        setting(state, "slot_capacity", "30");
        setting(state, "cancellation_cutoff_minutes", "10");
        setting(state, "canteen_open", "07:30");
        setting(state, "canteen_close", "15:00");

        String[][] menu = {
            {"Idli Sambar", "Steamed idlis with sambar and chutney", "Breakfast", "35", "60", "https://images.unsplash.com/photo-1589301760014-d929f3979dbc?auto=format&fit=crop&w=700&q=80"},
            {"Masala Dosa", "Crisp dosa filled with spiced potato", "Breakfast", "55", "45", "https://images.unsplash.com/photo-1668236543090-82eba5ee5976?auto=format&fit=crop&w=700&q=80"},
            {"Medu Vada", "Crispy lentil doughnuts with chutney", "Breakfast", "30", "50", "https://images.unsplash.com/photo-1606491956689-2ea866880c84?auto=format&fit=crop&w=700&q=80"},
            {"Veg Meals", "Rice, seasonal curry, dal and sides", "Meals", "90", "40", "https://images.unsplash.com/photo-1546833999-b9f581a1996d?auto=format&fit=crop&w=700&q=80"},
            {"Paneer Biryani", "Fragrant basmati rice with paneer", "Meals", "120", "30", "https://images.unsplash.com/photo-1563379091339-03246963d96c?auto=format&fit=crop&w=700&q=80"},
            {"Veg Fried Rice", "Wok-tossed rice with garden vegetables", "Meals", "85", "35", "https://images.unsplash.com/photo-1603133872878-684f208fb84b?auto=format&fit=crop&w=700&q=80"},
            {"Samosa (2 pcs)", "Golden pastry with potato filling", "Snacks", "25", "80", "https://images.unsplash.com/photo-1601050690597-df0568f70950?auto=format&fit=crop&w=700&q=80"},
            {"Veg Sandwich", "Grilled sandwich with fresh vegetables", "Snacks", "45", "35", "https://images.unsplash.com/photo-1528735602780-2552fd46c7af?auto=format&fit=crop&w=700&q=80"},
            {"French Fries", "Crispy salted potato fries", "Snacks", "50", "30", "https://images.unsplash.com/photo-1573080496219-bb080dd4f877?auto=format&fit=crop&w=700&q=80"},
            {"Hakka Noodles", "Street-style noodles with vegetables", "Fast Food", "80", "30", "https://images.unsplash.com/photo-1585032226651-759b368d7246?auto=format&fit=crop&w=700&q=80"},
            {"Veg Burger", "Crispy veg patty, lettuce and house sauce", "Fast Food", "70", "25", "https://images.unsplash.com/photo-1568901346375-23c9450c58cd?auto=format&fit=crop&w=700&q=80"},
            {"Margherita Pizza", "Classic cheese and tomato personal pizza", "Fast Food", "110", "20", "https://images.unsplash.com/photo-1574071318508-1cdbab80d002?auto=format&fit=crop&w=700&q=80"},
            {"Masala Chai", "Freshly brewed Indian tea", "Drinks", "15", "100", "https://images.unsplash.com/photo-1571934811356-5cc061b6821f?auto=format&fit=crop&w=700&q=80"},
            {"Filter Coffee", "South Indian filter coffee", "Drinks", "25", "80", "https://images.unsplash.com/photo-1509042239860-f550ce710b93?auto=format&fit=crop&w=700&q=80"},
            {"Fresh Lime Soda", "Refreshing lime with soda", "Drinks", "30", "40", "https://images.unsplash.com/photo-1513558161293-cfaf765edfd7?auto=format&fit=crop&w=700&q=80"}
        };
        for (String[] item : menu) {
            Record food = state.create("food");
            food.set("name", item[0]);
            food.set("description", item[1]);
            food.set("category", item[2]);
            food.set("price", item[3]);
            food.set("stockQuantity", item[4]);
            food.set("imageUrl", item[5]);
            food.set("available", "true");
            state.add(food);
        }

        int duration = 5;
        LocalTime now = LocalTime.now().plusMinutes(10);
        int roundedMinute = ((now.getMinute() + duration - 1) / duration) * duration;
        LocalTime start = now.withSecond(0).withNano(0).withMinute(0).plusMinutes(roundedMinute);
        LocalDate date = LocalDate.now();
        if (start.isBefore(now) || !start.plusMinutes(duration * 12L).isBefore(LocalTime.MAX)) {
            date = date.plusDays(1);
            start = LocalTime.of(12, 30);
        }
        for (int i = 0; i < 12; i++) {
            Record slot = state.create("slot");
            slot.set("slotDate", date.toString());
            slot.set("startTime", start.plusMinutes(i * duration).toString());
            slot.set("endTime", start.plusMinutes((i + 1L) * duration).toString());
            slot.set("capacity", "30");
            slot.set("reservedCount", "0");
            slot.set("available", "true");
            state.add(slot);
        }
        return state;
    }

    private static void setting(State state, String key, String value) {
        Record setting = state.create("setting");
        setting.set("settingKey", key);
        setting.set("settingValue", value);
        state.add(setting);
    }

    private static IOException storageError(IOException exception) {
        return new IOException("Could not read or write the CampusBite CSV data file.", exception);
    }

    @FunctionalInterface
    public interface Reader<T> {
        T apply(State state) throws IOException;
    }

    @FunctionalInterface
    public interface Writer<T> {
        T apply(State state) throws IOException;
    }

    public static final class ConflictException extends IOException {
        public ConflictException(String message) {
            super(message);
        }
    }

    public static final class State {
        private final List<Record> records;

        private State(List<Record> records) {
            this.records = records;
        }

        public List<Record> all(String type) {
            return records.stream().filter(record -> type.equals(record.get("type"))).toList();
        }

        public Record find(String type, long id) {
            return records.stream()
                    .filter(record -> type.equals(record.get("type")) && record.longValue("id") == id)
                    .findFirst().orElse(null);
        }

        public Record create(String type) {
            long nextId = records.stream().filter(record -> type.equals(record.get("type")))
                    .mapToLong(record -> record.longValue("id")).max().orElse(0) + 1;
            Record record = new Record();
            record.set("type", type);
            record.set("id", Long.toString(nextId));
            record.set("createdAt", LocalDateTime.now().toString());
            return record;
        }

        public void add(Record record) {
            records.add(record);
        }

        public boolean remove(Record record) {
            return records.remove(record);
        }

        private List<Record> records() {
            return records;
        }
    }

    public static final class Record {
        private final Map<String, String> values = new LinkedHashMap<>();

        private Record() {
            for (String column : COLUMNS) {
                values.put(column, "");
            }
        }

        public String get(String column) {
            return values.getOrDefault(column, "");
        }

        public void set(String column, String value) {
            if (!values.containsKey(column)) {
                throw new IllegalArgumentException("Unknown CSV field: " + column);
            }
            values.put(column, value == null ? "" : value);
        }

        public long longValue(String column) {
            return get(column).isBlank() ? 0 : Long.parseLong(get(column));
        }

        public int intValue(String column) {
            return get(column).isBlank() ? 0 : Integer.parseInt(get(column));
        }

        public boolean booleanValue(String column) {
            return Boolean.parseBoolean(get(column));
        }

        public BigDecimal decimalValue(String column) {
            return new BigDecimal(get(column));
        }

        public LocalDate dateValue(String column) {
            return LocalDate.parse(get(column));
        }

        public LocalTime timeValue(String column) {
            return LocalTime.parse(get(column));
        }
    }
}
