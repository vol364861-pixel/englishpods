package com.englishpods.task.service;

import com.englishpods.task.repository.TaskRepository;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TaskAudioCleanup {
    private final TaskRepository repository;
    private final Path audioRoot;

    public TaskAudioCleanup(TaskRepository repository, @Value("${story.audio-dir:data/audio}") String audioRoot) {
        this.repository = repository;
        this.audioRoot = Path.of(audioRoot).toAbsolutePath().normalize();
    }

    @Scheduled(fixedDelay = 30_000)
    public void retryPending() {
        repository.pendingAudioCleanup().forEach(this::deleteFiles);
    }

    public synchronized boolean deleteFiles(UUID id) {
        Path taskDirectory = audioRoot.resolve(id.toString());
        try {
            if (!Files.notExists(taskDirectory, LinkOption.NOFOLLOW_LINKS)) {
                Path expected = audioRoot.toRealPath().resolve(id.toString());
                Files.walkFileTree(taskDirectory, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                        if (!path.toRealPath().equals(expected.resolve(taskDirectory.relativize(path)))) {
                            throw new IOException("Audio directory resolves outside the expected path.");
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path folder, IOException failure) throws IOException {
                        if (failure != null) throw failure;
                        Files.delete(folder);
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            repository.finishAudioCleanup(id);
            return true;
        } catch (IOException | DataAccessException exception) {
            // Leave the durable cleanup record for a later attempt, including after restart.
            return false;
        }
    }
}
