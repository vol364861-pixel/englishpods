package com.englishpods.generation;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** All offsets are derived from actual normalized PCM frames, including inserted silence. */
final class AudioAssembler {
    record Part(double offset, double duration) {}
    private static final int SAMPLE_RATE = 24000;
    private final String ffmpeg;
    private final Path directory;
    private final int pauseMillis;
    private long frames;

    AudioAssembler(String ffmpeg, Path directory, int pauseMillis) throws IOException {
        this.ffmpeg = ffmpeg; this.directory = directory.toAbsolutePath(); this.pauseMillis = pauseMillis;
        Files.write(this.directory.resolve("combined.pcm"), new byte[0]);
    }

    Part append(byte[] bytes, String chunkName, boolean pauseAfter) {
        try {
            Path input = directory.resolve("chunk-" + chunkName + ".audio");
            Path normalized = directory.resolve("chunk-" + chunkName + ".wav");
            Files.write(input, bytes);
            run(List.of("-i", input.toString(), "-map", "0:a:0", "-vn", "-ac", "1", "-ar", "24000",
                    "-c:a", "pcm_s16le", "-f", "wav", normalized.toString()));
            double offset = frames / (double) SAMPLE_RATE;
            long addedFrames;
            try (var audio = AudioSystem.getAudioInputStream(normalized.toFile());
                 var output = Files.newOutputStream(directory.resolve("combined.pcm"), StandardOpenOption.APPEND)) {
                AudioFormat format = audio.getFormat();
                if (format.getSampleRate() != SAMPLE_RATE || format.getChannels() != 1 || format.getFrameSize() != 2
                        || format.isBigEndian() || !format.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED))
                    throw new GenerationException("FFmpeg 音频格式不符合24kHz单声道PCM要求");
                long byteCount = audio.transferTo(output);
                if (byteCount == 0 || byteCount % 2 != 0) throw new GenerationException("语音音频为空或帧损坏");
                addedFrames = byteCount / 2;
                frames += addedFrames;
                if (frames > SAMPLE_RATE * 3600L) throw new GenerationException("故事音频超过MVP一小时限制");
                if (pauseAfter) {
                    int silenceFrames = SAMPLE_RATE * pauseMillis / 1000;
                    output.write(new byte[silenceFrames * 2]);
                    frames += silenceFrames;
                }
            }
            return new Part(offset, addedFrames / (double) SAMPLE_RATE);
        } catch (GenerationException e) { throw e; }
        catch (Exception e) { throw new GenerationException("语音音频无法解码或写入，请重试"); }
    }

    double finish() {
        try {
            Path temporary = directory.resolve("story.tmp.wav");
            run(List.of("-f", "s16le", "-ar", "24000", "-ac", "1", "-i", directory.resolve("combined.pcm").toString(),
                    "-c:a", "pcm_s16le", "-f", "wav", temporary.toString()));
            try (var audio = AudioSystem.getAudioInputStream(temporary.toFile())) {
                if (audio.getFrameLength() != frames) throw new GenerationException("FFmpeg 合并后音频帧数不一致");
            }
            Files.move(temporary, directory.resolve("story.wav"), StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(directory.resolve("combined.pcm"));
            return frames / (double) SAMPLE_RATE;
        } catch (GenerationException e) { throw e; }
        catch (Exception e) { throw new GenerationException("最终音频无法保存，请检查磁盘空间"); }
    }

    private void run(List<String> arguments) {
        List<String> command = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-nostdin"));
        command.addAll(arguments);
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) throw new GenerationException("FFmpeg 音频处理超时");
            if (process.exitValue() != 0) throw new GenerationException("FFmpeg 无法处理供应商返回的音频");
        } catch (IOException e) { throw new GenerationException("无法启动 FFmpeg，请检查 FFMPEG_PATH"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new GenerationException("音频处理被中断，可重新生成"); }
        finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
    }
}
