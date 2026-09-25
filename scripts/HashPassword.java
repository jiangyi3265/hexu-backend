import java.io.Console;
import java.util.Arrays;
import org.springframework.security.crypto.bcrypt.BCrypt;

/** Run locally with JDK 17 and the Maven-resolved spring-security-crypto JAR. */
class HashPassword {
    public static void main(String[] args) {
        Console console = System.console();
        if (console == null) throw new IllegalStateException("Run from an interactive terminal; passwords must not be passed as arguments.");
        char[] password = console.readPassword("New admin password (at least 12 characters): ");
        try {
            if (password == null || password.length < 12) throw new IllegalArgumentException("Use at least 12 characters.");
            System.out.println(BCrypt.hashpw(new String(password), BCrypt.gensalt(12)));
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
    }
}
