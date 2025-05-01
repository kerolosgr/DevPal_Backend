package com.Dev.Pal.Services;

import com.Dev.Pal.Model.EmailToken;
import com.Dev.Pal.Model.UserEntity;
import com.Dev.Pal.Repositary.EmailTokenRepository;
import com.Dev.Pal.Repositary.UserRepository;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.warrenstrange.googleauth.GoogleAuthenticator;
import com.warrenstrange.googleauth.GoogleAuthenticatorConfig;
import com.warrenstrange.googleauth.GoogleAuthenticatorKey;
import com.warrenstrange.googleauth.GoogleAuthenticatorQRGenerator;
import jakarta.mail.MessagingException;
import lombok.AllArgsConstructor;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

@Service
public class OtpServices {

    private final GoogleAuthenticator googleAuthenticator;
    private final EmailTokenRepository emailTokenRepository;
    private final JwtEncoder jwtEncoder;
    private final JwtDecoder jwtDecoder;
    private final UserRepository userRepository;
    private final EmailServices emailService;

    public OtpServices(EmailTokenRepository emailTokenRepository,
                       JwtEncoder jwtEncoder,
                       JwtDecoder jwtDecoder,
                       UserRepository userRepository,
                       EmailServices emailService) {

        GoogleAuthenticatorConfig config = new GoogleAuthenticatorConfig.GoogleAuthenticatorConfigBuilder()
                .setWindowSize(3)  // 3 time-steps (90 seconds total window)
                .build();

        this.googleAuthenticator = new GoogleAuthenticator(config);
        this.emailTokenRepository = emailTokenRepository;
        this.jwtEncoder = jwtEncoder;
        this.jwtDecoder = jwtDecoder;
        this.userRepository = userRepository;
        this.emailService = emailService;
    }

    public String generateSecretKey(Long id) throws WriterException, MessagingException, IOException {
        UserEntity user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if(user.getSecret_key() != null) {
            throw new RuntimeException("QR Code already generated for this user");
        }

        GoogleAuthenticatorKey key = googleAuthenticator.createCredentials();
        String secret = key.getKey();
        user.setSecret_key(secret);
        userRepository.save(user);

        String otpAuthUrl = GoogleAuthenticatorQRGenerator.getOtpAuthURL(
                "DevPal",
                user.getEmail(),
                key
        );

        // Generate QR code (optional)
        QRCodeWriter qrCodeWriter = new QRCodeWriter();
        BitMatrix bitMatrix = qrCodeWriter.encode(
                otpAuthUrl,
                BarcodeFormat.QR_CODE,
                250,
                250
        );

        // Send email with QR code
        emailService.sendHtmlEmail(user.getEmail(), otpAuthUrl,id);

        return otpAuthUrl;
    }

    public Map<String, Object> validateQrCode(String code, Long id) {
        if (code == null || code.isEmpty()) {
            throw new RuntimeException("OTP code cannot be empty");
        }

        try {
            Integer.parseInt(code); // Validate numeric format
        } catch (NumberFormatException e) {
            throw new RuntimeException("OTP code must be numeric");
        }

        UserEntity userEntity = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));

        String secretKey = userEntity.getSecret_key();
        if (secretKey == null || secretKey.isEmpty()) {
            throw new RuntimeException("User does not have a secret key configured");
        }

        boolean isValid = googleAuthenticator.authorize(secretKey, Integer.parseInt(code));

        if (!isValid) {
            throw new RuntimeException("Invalid OTP Code");
        }

        return createJwtResponse(userEntity.getEmail(), id);
    }

    private Map<String, Object> createJwtResponse(String email, Long id) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("self")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(3, ChronoUnit.HOURS))
                .subject(email)
                .claim("Email", email)
                .claim("userId", id)
                .build();

        String token = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();

        Map<String, Object> response = new HashMap<>();
        response.put("token", token);
        response.put("userId", id);
        return response;
    }

    public Long getUserIdFromAuthorizationHeader(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new RuntimeException("Invalid authorization header");
        }

        String token = authorizationHeader.replace("Bearer ", "");
        Jwt decodedJwt = jwtDecoder.decode(token);
        return decodedJwt.getClaim("userId");
    }
}
