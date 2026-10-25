package com.scpl.web.form;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

/** Backups hold no logins (see BackupDocument), so a restore needs fresh founding admins for the new society. */
@Data
public class RestoreForm {

    private MultipartFile file;
    @Size(max = 120)
    private String newName;

    @Valid
    @Size(min = 3, max = 10, message = "a restored society needs between 3 and 10 admins")
    private List<SubscribeForm.AdminForm> admins = new ArrayList<>(List.of(
            new SubscribeForm.AdminForm(), new SubscribeForm.AdminForm(), new SubscribeForm.AdminForm()));
}