package com.atlasmind.atlaswatch.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service // Without this a bean is not created, as a result Autowired does not work.
public class EmailService {
    @Autowired // Without this javaMailSender will be null as the bean won't be injected in this.
    // Spring cannot inject into a final field. So make sure it's not final.
    // If multiple beans of the same type, use @Primary on @Bean method in config file for default or @Qualifier("bean method name") here specifying which one.
    private JavaMailSender javaMailSender;

    public void sendVerificationEmail(String recipient, String subject, String text) throws MessagingException {
        MimeMessage message = javaMailSender.createMimeMessage();
        MimeMessageHelper messageHelper = new MimeMessageHelper(message, true); // Multipart to allow multiple types of content!!

        messageHelper.setTo(recipient);
        messageHelper.setSubject(subject);
        messageHelper.setText(text, true);

        javaMailSender.send(message);
    }
}

