package com.Dev.Pal.Services;

import com.Dev.Pal.Model.JobResponse;
import com.Dev.Pal.Model.JobResult;
import com.Dev.Pal.Model.UserEntity;
import com.Dev.Pal.Repositary.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.AllArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@AllArgsConstructor
public class EmailServices {
    private final JavaMailSender mailSender;
    private final UserRepository userRepository;
    private final RestTemplate restTemplate;

    @Async
    public void sendHtmlEmail(String receiver, String link,Long id) throws MessagingException, IOException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true);

        helper.setFrom("devpal.team@gmail.com");
        helper.setTo(receiver);
        helper.setSubject("Welcome to DevPal");

        String emailContent = readTemplate("/templates/Devpal_Email.html");
        if (emailContent == null) return;
        // get user by email

        emailContent = emailContent.replace("{{link}}", link);
        emailContent = emailContent.replace("{{linkportal}}","https://devpal-alpha.vercel.app/discover/"+id);
        emailContent = emailContent.replace("{{userid}}", String.valueOf(id));
        helper.setText(emailContent, true);
        mailSender.send(message);
    }
    @Async
    public void sendCronJobEmailWithJobs(String receiver, String link, List<JobResult> jobs) throws MessagingException, IOException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true);

        helper.setFrom("devpal.team@gmail.com");
        helper.setTo(receiver);
        helper.setSubject("Job Opportunities from DevPal");

        String emailContent = readTemplate("/templates/DevpalCron.html");
        String jobCardTemplate = readTemplate("/templates/JobCard.html");

        if (emailContent == null || jobCardTemplate == null) return;

        StringBuilder jobDetails = new StringBuilder();
        for (JobResult job : jobs) {
            String jobHtml = jobCardTemplate
                    .replace("{{image_url}}", job.getImage_url())
                    .replace("{{job_title}}", job.getJob_title())
                    .replace("{{location}}", job.getLocation())
                    .replace("{{job_url}}", job.getJob_url());

            jobDetails.append(jobHtml);
        }

        emailContent = emailContent.replace("{{link}}", link);
        emailContent = emailContent.replace("{{job_count}}", String.valueOf(jobs.size()));
        emailContent = emailContent.replace("{{jobs_list}}", jobDetails.toString());

        helper.setText(emailContent, true);
        helper.addInline("dynamic-image", new ClassPathResource("static/Devpal_logo.PNG"));

        mailSender.send(message);
    }
    //only verfied

    @Scheduled(cron = "0 */5 * * * *")  // Midnight every Sunday
    public void sendEmailCronJobs() throws IOException, MessagingException {
        List<UserEntity> userEntities = userRepository.findAll();

        for (UserEntity currentUser : userEntities) {
           Boolean currentUserIsVerified =  currentUser.getIsVerified() ;
           if(!currentUserIsVerified) continue;

           String careerName = currentUser.getCareerName();
           String scrapedJobs = extracted(careerName);
            System.out.println(scrapedJobs);
            if (scrapedJobs == null) continue;

            ObjectMapper objectMapper = new ObjectMapper();
            JobResponse jobResponse = objectMapper.readValue(scrapedJobs, JobResponse.class);
            System.out.println("Raw response: " + jobResponse);



            if (jobResponse.getResult() == null || jobResponse.getResult().isEmpty()) {
                System.out.println("No jobs found for " + currentUser.getEmail());
                continue;
            }

            sendCronJobEmailWithJobs(currentUser.getEmail(), "https://devpal-alpha.vercel.app/start", jobResponse.getResult());
            jobResponse.getResult().clear();
        }
    }


    private String extracted(String careerName) {


        String nlpUrl = "https://web-production-1fe7.up.railway.app/scrape-jobs";

        // Build URI with query parameters
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(nlpUrl)
                .queryParam("query", careerName)
                .queryParam("page", 2);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    builder.build().toUri(),
                    HttpMethod.GET,
                    new HttpEntity<>(headers), // No need for request body with GET
                    String.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                return response.getBody();
            } else {
                String errorMsg = "Job scraping failed with status: " + response.getStatusCode();
                System.err.println(errorMsg);
                return errorMsg;
            }
        } catch (RestClientException e) {
            String errorMsg = "Error while fetching jobs: " + e.getMessage();
            System.err.println(errorMsg);
            return errorMsg;
        }
    }

    private String readTemplate(String path) {
        try (InputStream inputStream = getClass().getResourceAsStream(path)) {
            if (inputStream == null) {
                System.err.println("Template not found: " + path);
                return null;
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Failed to load template: " + path + " -> " + e.getMessage());
            return null;
        }
    }
}
