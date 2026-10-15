package edu.campusbite.service;

import edu.campusbite.dao.UserDao;
import edu.campusbite.model.User;
import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;
import org.mindrot.jbcrypt.BCrypt;

public class AuthService {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private final UserDao users = new UserDao();

    public User register(String name, String email, String password) throws IOException {
        String safeName = name == null ? "" : name.trim();
        String safeEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (safeName.length() < 2 || safeName.length() > 100)
            throw new IllegalArgumentException("Name must be 2 to 100 characters.");
        if (!EMAIL.matcher(safeEmail).matches() || safeEmail.length() > 190)
            throw new IllegalArgumentException("Enter a valid email address.");
        if (password == null || password.length() < 8 || password.length() > 72)
            throw new IllegalArgumentException("Password must be 8 to 72 characters.");
        return users.create(safeName, safeEmail, BCrypt.hashpw(password, BCrypt.gensalt(12)));
    }

    public User login(String email, String password) throws IOException {
        String safeEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        String hash = users.passwordHash(safeEmail);
        if (hash == null || password == null || !BCrypt.checkpw(password, hash)) return null;
        return users.findByEmail(safeEmail);
    }
}
