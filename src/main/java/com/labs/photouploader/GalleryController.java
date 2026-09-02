package com.labs.photouploader;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The whole app: one page listing photos (also the ALB health check
 * path -- a database outage correctly fails "/" with a 5xx rather than
 * silently reporting healthy), and one upload endpoint. No authentication,
 * per the lab spec.
 */
@Controller
public class GalleryController {

    private static final Pattern UNSAFE_FILENAME_CHARS = Pattern.compile("[^a-zA-Z0-9._-]");

    private final PhotoRepository photoRepository;
    private final S3Client s3Client;
    private final String bucketName;
    private final String cloudFrontDomain;
    private final String appOwnerName;
    private final String labName;

    public GalleryController(
            PhotoRepository photoRepository,
            S3Client s3Client,
            @Value("${app.photos-bucket-name}") String bucketName,
            @Value("${app.cloudfront-domain}") String cloudFrontDomain,
            @Value("${app.owner-name}") String appOwnerName,
            @Value("${app.lab-name}") String labName) {
        this.photoRepository = photoRepository;
        this.s3Client = s3Client;
        this.bucketName = bucketName;
        this.cloudFrontDomain = cloudFrontDomain;
        this.appOwnerName = appOwnerName;
        this.labName = labName;
    }

    @GetMapping("/")
    public String gallery(Model model) {
        List<Photo> photos = photoRepository.findAllByOrderByUploadedAtDesc();
        model.addAttribute("photos", photos);
        model.addAttribute("cloudFrontDomain", cloudFrontDomain);
        model.addAttribute("appOwnerName", appOwnerName);
        model.addAttribute("labName", labName);
        return "gallery";
    }

    @PostMapping("/upload")
    public String upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "description", required = false, defaultValue = "") String description,
            RedirectAttributes redirectAttributes) throws IOException {

        if (file.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Please choose an image file to upload.");
            return "redirect:/";
        }

        String originalFilename = file.getOriginalFilename() == null ? "photo" : file.getOriginalFilename();
        String safeFilename = UNSAFE_FILENAME_CHARS.matcher(originalFilename).replaceAll("_");
        String s3Key = UUID.randomUUID() + "-" + safeFilename;

        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(s3Key)
                        .contentType(file.getContentType())
                        .build(),
                RequestBody.fromInputStream(file.getInputStream(), file.getSize()));

        photoRepository.save(new Photo(s3Key, originalFilename, description, file.getContentType()));

        redirectAttributes.addFlashAttribute("success", "Photo uploaded.");
        return "redirect:/";
    }
}
