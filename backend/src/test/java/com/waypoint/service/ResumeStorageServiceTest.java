package com.waypoint.service;

import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSFindIterable;
import com.mongodb.client.gridfs.model.GridFSFile;
import com.mongodb.client.gridfs.model.GridFSUploadOptions;
import com.waypoint.exception.ResourceNotFoundException;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.http.MediaType;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResumeStorageServiceTest {

    @Test
    void loadsResumeFromConfiguredStorageRoot(@TempDir Path storageRoot) throws Exception {
        Path resume = storageRoot.resolve("resumes/alex.pdf");
        Files.createDirectories(resume.getParent());
        Files.writeString(resume, "resume");

        ResumeStorageService.ResumeDocument document = new ResumeStorageService(storageRoot.toString())
                .load("resumes/alex.pdf");

        assertEquals("alex.pdf", document.fileName());
        assertEquals("resume", new String(document.resource().getContentAsByteArray()));
        assertEquals(6, document.contentLength());
    }

    @Test
    void rejectsPathsOutsideConfiguredStorageRoot(@TempDir Path storageRoot) {
        ResumeStorageService service = new ResumeStorageService(storageRoot.toString());

        assertThrows(ResourceNotFoundException.class, () -> service.load("../private/alex.pdf"));
    }

    @Test
    void storesUploadedResumeInProfileScopedStorage(@TempDir Path storageRoot) {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "Alex Resume.pdf",
                MediaType.APPLICATION_PDF_VALUE,
                "resume".getBytes());

        ResumeStorageService.StoredResume storedResume = new ResumeStorageService(storageRoot.toString())
                .store(file, "jobSeeker-profile-1");

        assertEquals("Alex_Resume.pdf", storedResume.fileName());
        assertEquals(6, storedResume.contentLength());
        assertTrue(storedResume.storedPath().startsWith("resumes/jobSeeker-profile-1/"));
        assertTrue(Files.isRegularFile(storageRoot.resolve(storedResume.storedPath())));
    }

    @Test
    void findsExistingResumeForProfileWhenItsDatabasePathIsMissing(@TempDir Path storageRoot) throws Exception {
        Path resume = storageRoot.resolve("resumes/jobSeeker-profile-1/XSD_.docx");
        Files.createDirectories(resume.getParent());
        Files.writeString(resume, "resume");

        assertEquals(
                "resumes/jobSeeker-profile-1/XSD_.docx",
                new ResumeStorageService(storageRoot.toString())
                        .findLatestStoredPath("jobSeeker-profile-1")
                        .orElseThrow());
    }

    @Test
    void rejectsUnsupportedResumeType(@TempDir Path storageRoot) {
        MockMultipartFile file = new MockMultipartFile(
                "file", "Alex.txt", "text/plain", "resume".getBytes());

        assertThrows(com.waypoint.exception.BusinessException.class,
                () -> new ResumeStorageService(storageRoot.toString()).store(file, "jobSeeker-profile-1"));
    }

    @Test
    void storesUploadedResumeInSharedGridFs(@TempDir Path storageRoot) {
        GridFSBucket sharedStorage = mock(GridFSBucket.class);
        ObjectId objectId = new ObjectId("507f1f77bcf86cd799439011");
        when(sharedStorage.uploadFromStream(
                eq("Alex_Resume.pdf"), any(InputStream.class), any(GridFSUploadOptions.class)))
                .thenReturn(objectId);
        MockMultipartFile file = new MockMultipartFile(
                "file", "Alex Resume.pdf", MediaType.APPLICATION_PDF_VALUE, "resume".getBytes());

        ResumeStorageService.StoredResume storedResume = new ResumeStorageService(storageRoot.toString(), sharedStorage)
                .store(file, "jobSeeker-profile-1");

        assertEquals("gridfs/507f1f77bcf86cd799439011/Alex_Resume.pdf", storedResume.storedPath());
        assertEquals("Alex_Resume.pdf", storedResume.fileName());
        assertEquals(6, storedResume.contentLength());
        verify(sharedStorage).uploadFromStream(
                eq("Alex_Resume.pdf"), any(InputStream.class), any(GridFSUploadOptions.class));
        assertTrue(storedResume.storedPath().startsWith("gridfs/"));
    }

    @Test
    void checksSharedGridFsWhenResolvingAResume(@TempDir Path storageRoot) {
        GridFSBucket sharedStorage = mock(GridFSBucket.class);
        GridFSFindIterable files = mock(GridFSFindIterable.class);
        GridFSFile file = mock(GridFSFile.class);
        ObjectId objectId = new ObjectId("507f1f77bcf86cd799439011");
        when(sharedStorage.find(any(Bson.class))).thenReturn(files);
        when(files.first()).thenReturn(file);
        when(file.getObjectId()).thenReturn(objectId);

        assertTrue(new ResumeStorageService(storageRoot.toString(), sharedStorage)
                .exists("gridfs/507f1f77bcf86cd799439011/Alex_Resume.pdf"));
    }

    @Test
    void identifiesPathsThatCanBeResolvedAcrossComputers() {
        ResumeStorageService service = new ResumeStorageService("uploads");

        assertTrue(service.isSharedStoragePath("gridfs/507f1f77bcf86cd799439011/Alex_Resume.pdf"));
        assertFalse(service.isSharedStoragePath("resumes/jobSeeker-profile-1/Alex_Resume.pdf"));
    }
}
