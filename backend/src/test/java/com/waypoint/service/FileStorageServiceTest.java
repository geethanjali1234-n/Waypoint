package com.waypoint.service;

import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSFindIterable;
import com.mongodb.client.gridfs.model.GridFSFile;
import com.mongodb.client.gridfs.model.GridFSUploadOptions;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ResourceNotFoundException;
import org.bson.BsonObjectId;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileStorageServiceTest {

    @TempDir
    private Path tempDir;

    private FileStorageService fileStorageService;

    @BeforeEach
    void setUp() {
        fileStorageService = new FileStorageService(tempDir.toString());
    }

    @Test
    void throwsBusinessExceptionWhenFileIsNull() {
        assertThatThrownBy(() -> fileStorageService.store(null, "resumes", "owner-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("No file was provided");
    }

    @Test
    void throwsBusinessExceptionWhenFileIsEmpty() {
        MockMultipartFile emptyFile = new MockMultipartFile("file", "resume.pdf", "application/pdf", new byte[0]);

        assertThatThrownBy(() -> fileStorageService.store(emptyFile, "resumes", "owner-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("No file was provided");
    }

    @Test
    void storesFileUnderSubdirectoryAndOwnerIdAndReturnsRelativePath() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "resume.pdf", "application/pdf", "content".getBytes());

        String relativePath = fileStorageService.store(file, "resumes", "owner-1");

        assertThat(relativePath).isEqualTo("resumes/owner-1/resume.pdf");
        Path storedPath = tempDir.resolve("resumes").resolve("owner-1").resolve("resume.pdf");
        assertThat(Files.exists(storedPath)).isTrue();
        assertThat(Files.readString(storedPath)).isEqualTo("content");
    }

    @Test
    void sanitizesUnsafeCharactersInFileName() {
        MockMultipartFile file = new MockMultipartFile("file", "my resume (final)!.pdf", "application/pdf", "content".getBytes());

        String relativePath = fileStorageService.store(file, "resumes", "owner-1");

        assertThat(relativePath).isEqualTo("resumes/owner-1/my_resume__final__.pdf");
    }

    @Test
    void usesFallbackFileNameWhenOriginalFilenameIsMissing() {
        MockMultipartFile file = new MockMultipartFile("file", null, "application/pdf", "content".getBytes());

        String relativePath = fileStorageService.store(file, "resumes", "owner-1");

        assertThat(relativePath).isEqualTo("resumes/owner-1/file");
    }

    @Test
    void replacesExistingFileWhenStoringANewOneForTheSameOwner() {
        MockMultipartFile first = new MockMultipartFile("file", "old.pdf", "application/pdf", "old content".getBytes());
        MockMultipartFile second = new MockMultipartFile("file", "new.pdf", "application/pdf", "new content".getBytes());

        fileStorageService.store(first, "resumes", "owner-1");
        String relativePath = fileStorageService.store(second, "resumes", "owner-1");

        assertThat(relativePath).isEqualTo("resumes/owner-1/new.pdf");
        Path ownerDir = tempDir.resolve("resumes").resolve("owner-1");
        assertThat(ownerDir.toFile().list()).containsExactly("new.pdf");
    }

    @Test
    void removeDeletesAllFilesInOwnerDirectory() {
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", "content".getBytes());
        fileStorageService.store(file, "profile-pictures", "owner-1");
        Path ownerDir = tempDir.resolve("profile-pictures").resolve("owner-1");
        assertThat(ownerDir.toFile().list()).isNotEmpty();

        fileStorageService.remove("profile-pictures", "owner-1");

        assertThat(ownerDir.toFile().list()).isEmpty();
    }

    @Test
    void removeIsANoOpWhenOwnerDirectoryDoesNotExist() {
        assertThatCode(() -> fileStorageService.remove("profile-pictures", "missing-owner"))
                .doesNotThrowAnyException();
    }

    @Test
    void getMetadataReturnsNullForBlankOrNullPath() {
        assertThat(fileStorageService.getMetadata(null)).isNull();
        assertThat(fileStorageService.getMetadata("")).isNull();
        assertThat(fileStorageService.getMetadata("   ")).isNull();
    }

    @Test
    void getMetadataReturnsNullWhenFileDoesNotExist() {
        assertThat(fileStorageService.getMetadata("resumes/owner-1/missing.pdf")).isNull();
    }

    @Test
    void getMetadataReturnsFileNameAndSizeForExistingFile() {
        MockMultipartFile file = new MockMultipartFile("file", "resume.pdf", "application/pdf", "content".getBytes());
        String relativePath = fileStorageService.store(file, "resumes", "owner-1");

        FileStorageService.FileMetadata metadata = fileStorageService.getMetadata(relativePath);

        assertThat(metadata).isNotNull();
        assertThat(metadata.fileName()).isEqualTo("resume.pdf");
        assertThat(metadata.sizeBytes()).isEqualTo("content".getBytes().length);
        assertThat(metadata.uploadedAt()).isNotNull();
    }

    @Test
    void storesUploadedFileInSharedGridFsWhenConfigured() {
        GridFSBucket sharedStorage = mock(GridFSBucket.class);
        ObjectId objectId = new ObjectId("507f1f77bcf86cd799439011");
        GridFSFindIterable emptyResult = mock(GridFSFindIterable.class);
        when(sharedStorage.find(any(Bson.class))).thenReturn(emptyResult);
        when(sharedStorage.uploadFromStream(eq("avatar.png"), any(InputStream.class), any(GridFSUploadOptions.class)))
                .thenReturn(objectId);
        FileStorageService sharedFileStorageService = new FileStorageService(tempDir.toString(), sharedStorage);
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", "content".getBytes());

        String relativePath = sharedFileStorageService.store(file, "profile-pictures", "owner-1");

        assertThat(relativePath).isEqualTo("gridfs/507f1f77bcf86cd799439011/avatar.png");
        verify(sharedStorage).uploadFromStream(eq("avatar.png"), any(InputStream.class), any(GridFSUploadOptions.class));
    }

    @Test
    void removeDeletesMatchingFilesFromSharedStorage() {
        GridFSBucket sharedStorage = mock(GridFSBucket.class);
        GridFSFindIterable emptyResult = mock(GridFSFindIterable.class);
        when(sharedStorage.find(any(Bson.class))).thenReturn(emptyResult);
        FileStorageService sharedFileStorageService = new FileStorageService(tempDir.toString(), sharedStorage);

        sharedFileStorageService.remove("profile-pictures", "owner-1");

        verify(sharedStorage).find(any(Bson.class));
    }

    @Test
    void findsTheOwnersCurrentLocalFileByOwnerAndForgetsItOnceRemoved() {
        MockMultipartFile file = new MockMultipartFile("file", "signature.png", "image/png", "content".getBytes());
        String storedPath = fileStorageService.store(file, "user-signatures", "user-1");

        assertThat(fileStorageService.findStoredPath("user-signatures", "user-1")).contains(storedPath);
        assertThat(fileStorageService.findStoredPath("user-signatures", "someone-else")).isEmpty();

        fileStorageService.remove("user-signatures", "user-1");
        assertThat(fileStorageService.findStoredPath("user-signatures", "user-1")).isEmpty();
    }

    @Test
    void findsTheOwnersNewestSharedGridFsFileInTheSamePathFormStoreReturns() {
        GridFSBucket sharedStorage = mock(GridFSBucket.class);
        GridFSFindIterable result = mock(GridFSFindIterable.class);
        ObjectId objectId = new ObjectId("507f1f77bcf86cd799439011");
        GridFSFile stored = new GridFSFile(new BsonObjectId(objectId), "signature.png", 3, 255, new Date(),
                new Document("subdirectory", "user-signatures").append("ownerId", "user-1").append("fileName", "signature.png"));
        when(sharedStorage.find(any(Bson.class))).thenReturn(result);
        when(result.sort(any(Bson.class))).thenReturn(result);
        when(result.first()).thenReturn(stored);
        FileStorageService sharedFileStorageService = new FileStorageService(tempDir.toString(), sharedStorage);

        assertThat(sharedFileStorageService.findStoredPath("user-signatures", "user-1"))
                .contains("gridfs/507f1f77bcf86cd799439011/signature.png");
    }

    @Test
    void loadThrowsResourceNotFoundExceptionForBlankPath() {
        assertThatThrownBy(() -> fileStorageService.load(""))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void loadsLegacyLocalFileWhenGridFsPrefixIsAbsent() {
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", "content".getBytes());
        String relativePath = fileStorageService.store(file, "profile-pictures", "owner-1");

        FileStorageService.StoredFile loaded = fileStorageService.load(relativePath);

        assertThat(loaded.fileName()).isEqualTo("avatar.png");
        assertThat(new String(loaded.content())).isEqualTo("content");
    }
}
