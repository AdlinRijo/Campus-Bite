package edu.campusbite.dao;

import edu.campusbite.model.User;
import edu.campusbite.util.CsvDataStore;
import edu.campusbite.util.CsvDataStore.Record;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Locale;

public class UserDao {
    public User findByEmail(String email) throws IOException {
        String cleanEmail = normalize(email);
        return CsvDataStore.read(state -> {
            Record user = state.all("user").stream()
                    .filter(row -> cleanEmail.equals(row.get("email")) && row.booleanValue("active"))
                    .findFirst().orElse(null);
            return user == null ? null : toUser(user);
        });
    }

    public String passwordHash(String email) throws IOException {
        String cleanEmail = normalize(email);
        return CsvDataStore.read(state -> state.all("user").stream()
                .filter(row -> cleanEmail.equals(row.get("email")) && row.booleanValue("active"))
                .map(row -> row.get("passwordHash")).findFirst().orElse(null));
    }

    public User create(String name, String email, String passwordHash) throws IOException {
        String cleanEmail = normalize(email);
        return CsvDataStore.update(state -> {
            boolean exists = state.all("user").stream().anyMatch(row -> cleanEmail.equals(row.get("email")));
            if (exists) {
                throw new CsvDataStore.ConflictException("An account with this email already exists.");
            }
            Record user = state.create("user");
            user.set("name", name);
            user.set("email", cleanEmail);
            user.set("passwordHash", passwordHash);
            user.set("role", cleanEmail.equals(CsvDataStore.bootstrapAdminEmail()) ? "ADMIN" : "STUDENT");
            user.set("active", "true");
            user.set("createdAt", LocalDateTime.now().toString());
            state.add(user);
            return toUser(user);
        });
    }

    private static User toUser(Record user) {
        return new User(user.longValue("id"), user.get("name"), user.get("email"), user.get("role"));
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
