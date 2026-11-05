package org.leon.practicemodule.oj.sandbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Docker 沙箱：每次编译/运行都起一个 --rm 容器，跑完即焚。
 *
 * 隔离手段（都在一次 docker run 的参数里）：禁网、限内存、限 CPU、限进程数、禁提权。
 * 编译做一次，运行做 N 次（N=用例数），编译产物经挂载目录复用。
 *
 * 两个容易踩的坑：
 * 1. 超时只 kill docker CLI 不够，容器还在跑，必须再 docker kill <容器名>；
 * 2. 等进程结束才去读 stdout 会在输出很大时因管道缓冲区满而死锁，
 *    所以把输出重定向到文件，结束后再读，并限制最多读多少字节。
 */
@Component
public class DockerSandbox implements CodeSandbox {

    private static final String CONTAINER_WORKDIR = "/box";

    private final int compileMemoryMb;
    private final int compileTimeoutMs;
    private final int maxOutputBytes;

    public DockerSandbox(@Value("${oj.sandbox.compile-memory-mb:512}") int compileMemoryMb,
                         @Value("${oj.sandbox.compile-timeout-ms:30000}") int compileTimeoutMs,
                         @Value("${oj.sandbox.max-output-bytes:1048576}") int maxOutputBytes) {
        this.compileMemoryMb = compileMemoryMb;
        this.compileTimeoutMs = compileTimeoutMs;
        this.maxOutputBytes = maxOutputBytes;
    }

    @Override
    public CompileContext compile(String sourceCode, JudgeLanguage language) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("oj-");
            // 容器内以 root 运行，挂载目录必须对容器可写
            dir.toFile().setWritable(true, false);
            Files.writeString(dir.resolve(language.sourceFile()), sourceCode, StandardCharsets.UTF_8);

            ExecOutcome outcome = exec(language.image(), language.compileCommand(),
                    dir, "", compileTimeoutMs, compileMemoryMb);

            if (outcome.timedOut()) {
                throw new OjCompileException("编译超时");
            }
            if (outcome.exitCode() != 0) {
                String message = (outcome.stderr() + outcome.stdout()).strip();
                throw new OjCompileException(message.isBlank() ? "编译失败" : message);
            }
            return new CompileContext(dir, language);
        } catch (OjCompileException e) {
            deleteQuietly(dir);
            throw e;
        } catch (Exception e) {
            deleteQuietly(dir);
            throw new OjCompileException("编译异常：" + e.getMessage());
        }
    }

    @Override
    public RunResult run(CompileContext context, String input, int timeLimitMs, int memoryLimitMb) {
        ExecOutcome outcome = exec(context.language().image(),
                context.language().runCommand(memoryLimitMb),
                context.workDir(),
                input == null ? "" : input,
                timeLimitMs,
                memoryLimitMb);

        if (outcome.timedOut()) {
            return RunResult.timeout(outcome.elapsedMs());
        }
        if (outcome.exitCode() != 0) {
            String stderr = outcome.stderr().isBlank()
                    ? "退出码 " + outcome.exitCode()
                    : outcome.stderr();
            return RunResult.runtimeError(stderr, outcome.elapsedMs());
        }
        return RunResult.ok(outcome.stdout(), outcome.elapsedMs());
    }

    @Override
    public void cleanup(CompileContext context) {
        deleteQuietly(context.workDir());
    }

    private ExecOutcome exec(String image, String script, Path workDir,
                             String stdin, int timeoutMs, int memoryMb) {
        String containerName = "oj-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Path stdoutFile = null;
        Path stderrFile = null;
        Process process = null;
        long start = System.currentTimeMillis();
        try {
            stdoutFile = Files.createTempFile("oj-out-", ".log");
            stderrFile = Files.createTempFile("oj-err-", ".log");

            List<String> command = List.of(
                    "docker", "run", "--rm",
                    "--name", containerName,
                    "--network", "none",
                    "--memory", memoryMb + "m",
                    "--memory-swap", memoryMb + "m",
                    "--cpus", "1",
                    "--pids-limit", "64",
                    "--security-opt", "no-new-privileges",
                    "-i",
                    "-v", workDir.toAbsolutePath() + ":" + CONTAINER_WORKDIR,
                    "-w", CONTAINER_WORKDIR,
                    image,
                    "sh", "-c", script);

            process = new ProcessBuilder(command)
                    .redirectOutput(stdoutFile.toFile())
                    .redirectError(stderrFile.toFile())
                    .start();

            try (OutputStream os = process.getOutputStream()) {
                os.write(stdin.getBytes(StandardCharsets.UTF_8));
            }

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            long elapsed = System.currentTimeMillis() - start;

            if (!finished) {
                process.destroyForcibly();
                killContainer(containerName);
                return new ExecOutcome(true, -1, "", "", elapsed);
            }
            return new ExecOutcome(false, process.exitValue(),
                    readCapped(stdoutFile), readCapped(stderrFile), elapsed);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            if (process != null) {
                process.destroyForcibly();
            }
            killContainer(containerName);
            return new ExecOutcome(false, -1, "", "容器执行异常：" + e.getMessage(), elapsed);
        } finally {
            deleteQuietly(stdoutFile);
            deleteQuietly(stderrFile);
        }
    }

    private static void killContainer(String containerName) {
        try {
            new ProcessBuilder("docker", "kill", containerName)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    private String readCapped(Path file) {
        try {
            if (file == null || !Files.exists(file)) {
                return "";
            }
            byte[] bytes = Files.readAllBytes(file);
            int length = Math.min(bytes.length, maxOutputBytes);
            return new String(bytes, 0, length, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 删除临时目录。Windows 下容器刚被 kill 时可能还持有挂载句柄，
     * 立即删除会失败；这里重试几次，避免在 %TEMP% 里留下空壳目录。
     */
    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            if (!Files.exists(path)) {
                return;
            }
            try (var walk = Files.walk(path)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (Exception ignored) {
                            }
                        });
            } catch (Exception ignored) {
            }
            if (!Files.exists(path)) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private record ExecOutcome(boolean timedOut, int exitCode,
                               String stdout, String stderr, long elapsedMs) {
    }
}
