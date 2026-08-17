package com.atlasmind.atlaswatch.config;

import java.util.Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@Configuration // Factory for beans, the class itself is a bean as well!!
public class EmailConfiguration {
    //This is a form of dependency injection!!
    @Value("${spring.mail.username}")
    private String emailName;
    @Value("${spring.mail.password}")
    private String password;
    @Value("${spring.mail.host}")
    private String host;

    @Bean
    public JavaMailSender JavaMailSender() {
        // JavaMailSender is an interface so it can't be used directly, it needs an implementation, in this case an inbuilt one - JavaMailSenderImpl.
        // Possible duplication of code as app.prop file already defines all info, so Autowired should technically be enough,
        // as spring automatically defines the JavaMailSenderImpl implementation for JavaMailSender.
        JavaMailSenderImpl javaMailSender = new JavaMailSenderImpl();
        javaMailSender.setHost(host);
        javaMailSender.setPort(587);
        javaMailSender.setPassword(this.password);
        javaMailSender.setUsername(this.emailName);

        Properties properties = javaMailSender.getJavaMailProperties();
        properties.put("mail.transport.protocol", "smtp");
        properties.put("mail.smtp.auth", "true");
        properties.put("mail.smtp.starttls.enable", "true");
        properties.put("mail.debug", "false");
        return javaMailSender;
    }
}

