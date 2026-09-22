/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.configuration.CatalogProperties;
import com.evolveum.midpoint.integration.catalog.integration.OpenProjectClient;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethod;
import com.evolveum.midpoint.integration.catalog.service.retry.OperationResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;

/**
 * Puts the files a submission carries onto its work package, and keeps them in step with the revision
 * as it is edited. Only the catalog's own uploads are ever touched; what a reviewer attached is left
 * alone.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupportTicketAttachments {

    /** Name the tutorial is attached under, also named by the description. */
    public static final String TUTORIAL_ATTACHMENT = "tutorial.md";

    public static final String OBJECT_STATE_ADDED = "added";
    public static final String OBJECT_STATE_REPLACED = "replaced";
    public static final String OBJECT_STATE_REMOVED = "removed";

    private final OpenProjectClient openProjectClient;
    private final CatalogProperties catalogProperties;
    private final TutorialStorageService tutorialStorageService;
    private final SupportTicketComments comments;

    /** A file the submission carries, read and named as it will be attached. */
    private record SubmittedFile(String fileName, byte[] content, String contentType) {
    }

    /**
     * The files of one revision.
     *
     * @param complete whether every file the revision lists could be read, i.e. whether an attachment
     *                 missing from {@code files} really means the author withdrew it
     */
    private record SubmittedFiles(List<SubmittedFile> files, boolean complete) {
    }

    /** Everything the revision carries, for a work package that has just been opened. */
    public void attachAll(int workPackageId, IntegrationMethod method) {
        List<String> refused = new ArrayList<>();
        for (SubmittedFile file : collectFiles(method, workPackageId).files()) {
            if (addingAttachment(workPackageId, file).isObsolete()) {
                refused.add(file.fileName());
            }
        }
        commentOnRefusedFiles(workPackageId, method, refused);
    }

    /**
     * Brings a work package's files back in line with the revision after an edit. Only the catalog's
     * own uploads are touched, and only where the content differs; anything a reviewer attached is
     * left alone.
     */
    public List<String> refresh(int workPackageId, IntegrationMethod method) {
        SubmittedFiles submitted = collectFiles(method, workPackageId);

        Map<String, List<OpenProjectClient.Attachment>> ours;
        try {
            OptionalInt self = openProjectClient.findSelfId();
            if (self.isEmpty()) {
                // Without knowing which uploads are the catalog's own, every deletion is a guess at
                // somebody else's file and every upload is a duplicate. Better to leave it as it is.
                log.error("Support portal did not say who the catalog signs in as, so the files of work"
                        + " package {} were left as they are", workPackageId);
                commentOnUnsyncedFiles(workPackageId, "The support portal did not say which account the"
                        + " catalog signs in as, so its own uploads could not be told apart from anybody"
                        + " else's and the files here were left as they are.", List.of());
                return List.of();
            }
            String selfHref = "/" + self.getAsInt();
            ours = openProjectClient.listAttachments(workPackageId).stream()
                    .filter(attachment -> attachment.authorHref().endsWith(selfHref))
                    .collect(Collectors.groupingBy(OpenProjectClient.Attachment::fileName,
                            LinkedHashMap::new, Collectors.toList()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while reading the files of work package {} in the support portal", workPackageId, e);
            commentOnUnsyncedFiles(workPackageId, "Reading the files already attached here was"
                    + " interrupted, so they were left as they are.", List.of());
            return List.of();
        } catch (Exception e) {
            log.error("Could not read the files of work package {} in the support portal, so they were left as they are",
                    workPackageId, e);
            commentOnUnsyncedFiles(workPackageId, "The files already attached here could not be read from"
                    + " the support portal, so they were left as they are.", List.of());
            return List.of();
        }

        List<String> changes = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        List<String> stayedBehind = new ArrayList<>();
        boolean compared = true;
        for (SubmittedFile file : submitted.files()) {
            List<OpenProjectClient.Attachment> present = ours.remove(file.fileName());
            String digest = md5(file.content());
            compared &= digest != null;
            OpenProjectClient.Attachment same = present == null ? null : present.stream()
                    .filter(attachment -> digest != null && digest.equalsIgnoreCase(attachment.digest()))
                    .findFirst()
                    .orElse(null);

            if (same != null) {
                deleteOthersAttachments(workPackageId, present, same, stayedBehind);
                continue;
            }

            OperationResult attached = addingAttachment(workPackageId, file);
            if (!attached.isCompleted()) {
                if (attached.isObsolete()) {
                    refused.add(file.fileName());
                }
                continue;
            }
            boolean replaced = deleteOthersAttachments(workPackageId, present, null, stayedBehind);
            changes.add("`" + file.fileName() + "` " + (replaced ? OBJECT_STATE_REPLACED : OBJECT_STATE_ADDED));
        }

        commentOnRefusedFiles(workPackageId, method, refused);

        if (!compared) {
            commentOnUnsyncedFiles(workPackageId, "This catalog's Java cannot produce the digests the"
                    + " support portal reports, so the files here could not be compared with the"
                    + " submission's and were attached afresh instead. Anything listed as replaced may"
                    + " be unchanged.", List.of());
        }

        if (!submitted.complete()) {
            log.error("Not everything {}/{} carries could be read, so nothing was removed from work package {}",
                    method.getId(), method.getRevision(), workPackageId);
            commentOnUnsyncedFiles(workPackageId, "Not everything this revision carries could be read from"
                    + " the catalog, so nothing was removed here - a file that is no longer part of the"
                    + " submission may still be attached.", stayedBehind);
            return changes;
        }
        for (List<OpenProjectClient.Attachment> withdrawn : ours.values()) {
            for (OpenProjectClient.Attachment attachment : withdrawn) {
                switch (deleteAttachment(workPackageId, attachment)) {
                    case REMOVED -> changes.add("`" + attachment.fileName() + "` " + OBJECT_STATE_REMOVED);
                    case FAILED -> stayedBehind.add(attachment.fileName());
                    case ALREADY_GONE -> { /* the end state is the wanted one; nothing changed here */ }
                }
            }
        }
        if (!stayedBehind.isEmpty()) {
            commentOnUnsyncedFiles(workPackageId, "These files are no longer part of the submission, but"
                    + " the support portal would not remove them, so they are still attached here:",
                    stayedBehind);
        }
        return changes;
    }

    /** One named upload of the revision, for a file that arrived after the work package was opened. */
    public OperationResult attachStored(int workPackageId, IntegrationMethod method, String fileName) {
        Optional<SubmittedFile> file = readStoredFile(method, fileName, workPackageId);
        if (file.isEmpty()) {
            String message = "File " + fileName + " of " + method.getId() + "/"
                    + method.getRevision() + " could not be read from the catalog's storage.";
            log.error(message);
            return OperationResult.obsolete(message);
        }
        OperationResult attached = addingAttachment(workPackageId, file.get());
        if (attached.isObsolete()) {
            commentOnRefusedFiles(workPackageId, method, List.of(fileName));
        }
        return attached;
    }

    private SubmittedFiles collectFiles(IntegrationMethod method, int workPackageId) {
        List<SubmittedFile> files = new ArrayList<>();
        String tutorial = method.getTutorial();
        if (tutorial != null && !tutorial.isBlank()) {
            files.add(new SubmittedFile(TUTORIAL_ATTACHMENT,
                    tutorial.getBytes(StandardCharsets.UTF_8), "text/markdown"));
        }

        List<String> names;
        try {
            names = tutorialStorageService.listTutorialFiles(method.getId(), method.getRevision());
        } catch (Exception e) {
            log.warn("Could not list tutorial files of {}/{} to attach them to work package {}: {}",
                    method.getId(), method.getRevision(), workPackageId, e.getMessage());
            return new SubmittedFiles(files, false);
        }

        boolean complete = true;
        for (String name : names) {
            Optional<SubmittedFile> file = readStoredFile(method, name, workPackageId);
            if (file.isPresent()) {
                files.add(file.get());
            } else {
                complete = false;
            }
        }
        return new SubmittedFiles(files, complete);
    }

    /** One of the author's uploads, read off the disk, or empty when it cannot be read. */
    private Optional<SubmittedFile> readStoredFile(IntegrationMethod method, String fileName, int workPackageId) {
        try {
            Path file = tutorialStorageService.resolveTutorialFile(method.getId(), method.getRevision(), fileName);
            byte[] content = Files.readAllBytes(file);
            String probed = Files.probeContentType(file);
            return Optional.of(new SubmittedFile(fileName, content,
                    probed != null ? probed : "application/octet-stream"));
        } catch (Exception e) {
            log.error("Could not read file {} of {}/{} to attach it to work package {}",
                    fileName, method.getId(), method.getRevision(), workPackageId, e);
            return Optional.empty();
        }
    }

    private OperationResult addingAttachment(int workPackageId, SubmittedFile file) {
        try {
            openProjectClient.addAttachment(workPackageId, file.fileName(), file.content(), file.contentType());
            log.info("Attached {} ({} bytes) to support work package {}",
                    file.fileName(), file.content().length, workPackageId);
            return OperationResult.completed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while attaching {} to work package {} in the support portal", file.fileName(), workPackageId, e);
            return OperationResult.retry("Interrupted while attaching " + file.fileName()
                    + " to work package #" + workPackageId + ".");
        } catch (OpenProjectClient.AttachmentTooLargeException e) {
            log.error("Work package {} would not take {} ({} bytes) in the support portal: it is over the portal's"
                            + " attachment size limit, so it stays in the catalog only",
                    workPackageId, file.fileName(), file.content().length, e);
            return OperationResult.obsolete("Work package #" + workPackageId + " would not take "
                    + file.fileName() + " (" + file.content().length
                    + " bytes): it is over the support portal's attachment size limit.");
        } catch (Exception e) {
            log.error("Failed to attach {} to work package {} in the support portal",
                    file.fileName(), workPackageId, e);
            return OperationResult.retry("Could not attach " + file.fileName() + " to work package #"
                    + workPackageId + ": " + SupportTicketFailures.reason(e));
        }
    }

    /**
     * Tells the reviewer, on the work package itself, about files the portal would not take and where
     * to get them instead - otherwise the Files tab is simply short and nothing says why. One comment
     * per run, however many files it refused, so several oversized samples do not bury the review.
     */
    private void commentOnRefusedFiles(int workPackageId, IntegrationMethod method, List<String> refused) {
        if (refused.isEmpty()) {
            return;
        }
        boolean one = refused.size() == 1;
        StringBuilder body = new StringBuilder("**")
                .append(one ? "A file of this submission could not be attached"
                        : "Some files of this submission could not be attached")
                .append("**\n\n")
                .append("The support portal would not accept ")
                .append(one ? "this file, which is" : "these files, which are")
                .append(" over its attachment size limit:\n\n");
        for (String fileName : refused) {
            body.append("- `").append(fileName).append("`\n");
        }
        body.append("\nNothing is missing from the submission itself. ")
                .append(one ? "The file is" : "The files are")
                .append(" stored in the integration catalog and can be downloaded from the revision's page");

        String url = catalogProperties.integrationMethodUrl(
                method.getApplication() != null ? method.getApplication().getId() : null,
                method.getId(), method.getRevision());
        body.append(url != null ? ":\n\n" + url + "\n" : " in the catalog.\n");

        comments.post(workPackageId, Optional.of(body.toString()));
    }

    /**
     * Tells the reviewer that the Files tab does not necessarily match the submission, and why -
     * otherwise it is simply wrong with nothing saying so, and a file that looks withdrawn may only
     * have been unreachable. Durable: a portal that will not take the note now is asked again later.
     *
     * @param what the situation, in the reviewer's terms
     * @param files the files it concerns, when it concerns particular ones
     */
    private void commentOnUnsyncedFiles(int workPackageId, String what, List<String> files) {
        StringBuilder body = new StringBuilder("**The files attached here may not match the submission**\n\n")
                .append(what).append("\n");
        for (String file : files) {
            body.append("\n- `").append(file).append("`");
        }
        if (!files.isEmpty()) {
            body.append('\n');
        }
        body.append("\nThe submission itself is unaffected. Its files can be downloaded from the"
                + " revision's page in the integration catalog.\n");

        comments.post(workPackageId, Optional.of(body.toString()));
    }

    /** What became of one attempt to take a file off a work package. */
    private enum Removal {
        /** It was there and is not any more. */
        REMOVED,
        /** The portal had no such attachment, so the wanted end state already held. */
        ALREADY_GONE,
        /** The portal refused or could not be reached, so the file is presumably still attached. */
        FAILED
    }

    /**
     * @param stayedBehind collects the files the portal would not remove, for the note to the reviewer
     * @return whether anything was actually removed, which is what makes an upload a replacement
     */
    private boolean deleteOthersAttachments(int workPackageId, List<OpenProjectClient.Attachment> present,
                                            OpenProjectClient.Attachment keep, List<String> stayedBehind) {
        if (present == null) {
            return false;
        }
        boolean removed = false;
        for (OpenProjectClient.Attachment attachment : present) {
            if (attachment != keep) {
                switch (deleteAttachment(workPackageId, attachment)) {
                    case REMOVED -> removed = true;
                    case FAILED -> stayedBehind.add(attachment.fileName());
                    case ALREADY_GONE -> { /* nothing was replaced by taking away what was not there */ }
                }
            }
        }
        return removed;
    }

    private Removal deleteAttachment(int workPackageId, OpenProjectClient.Attachment attachment) {
        try {
            if (!openProjectClient.deleteAttachment(attachment.id())) {
                log.info("{} was already gone from support work package {}", attachment.fileName(), workPackageId);
                return Removal.ALREADY_GONE;
            }
            log.info("Removed {} from support work package {}", attachment.fileName(), workPackageId);
            return Removal.REMOVED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while removing {} from work package {} in support portal",
                    attachment.fileName(), workPackageId, e);
            return Removal.FAILED;
        } catch (Exception e) {
            log.error("Could not remove {} from work package {} in support portal",
                    attachment.fileName(), workPackageId, e);
            return Removal.FAILED;
        }
    }

    /**
     * The content's md5, which is the digest the portal reports for what it stores, or null when this
     * Java will not produce one. Every Java platform is required to support MD5, so that is all but
     * unreachable - a FIPS-restricted one withholding it is the way it happens. A null digest matches
     * nothing, so the files are attached afresh instead of compared, and {@link #refresh} says so on
     * the work package rather than leaving a list of changes that did not happen.
     */
    private static String md5(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(content));
        } catch (NoSuchAlgorithmException e) {
            log.error("No md5 available, so attached files cannot be compared", e);
            return null;
        }
    }
}
