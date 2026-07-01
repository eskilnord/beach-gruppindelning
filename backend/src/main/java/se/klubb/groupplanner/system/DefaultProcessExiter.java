package se.klubb.groupplanner.system;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Production {@link ProcessExiter}: closes the Spring context gracefully via {@link
 * SpringApplication#exit} and then terminates the JVM with the resulting exit code.
 */
@Component
public class DefaultProcessExiter implements ProcessExiter {

    @Override
    public void exit(ConfigurableApplicationContext context, int exitCode) {
        int code = SpringApplication.exit(context, () -> exitCode);
        System.exit(code);
    }
}
