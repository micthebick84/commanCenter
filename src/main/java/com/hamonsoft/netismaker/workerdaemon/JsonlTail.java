package com.hamonsoft.netismaker.workerdaemon;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * jsonl 파일을 바이트 위치부터 완성된 줄 단위로 읽는다(Claude Code 사용량 수집의 이어 읽기).
 * <ul>
 *   <li>위치는 바이트 단위다 — 문자 수(BufferedReader)로는 셀 수 없다. 줄은 0x0A로 나누고 끝 {@code \r}은 뗀다.</li>
 *   <li>개행이 없는 마지막 조각은 아직 쓰이는 중일 수 있어 돌려주지 않는다. {@code nextOffset}은 마지막 완성 줄의 개행 다음 바이트다.</li>
 *   <li>읽은 바이트가 상한 이상이 되면 그 줄 경계에서 멈춘다. 상한보다 큰 줄이라도 완성됐으면 한 줄은 돌려준다 — 같은 자리에서 영원히 멈추지 않게.</li>
 *   <li>{@code FileChannel.open}으로만 연다(NIO는 삭제 공유를 허용 — Claude Code의 30일 정리를 막지 않는다). 파일이 없으면 {@code NoSuchFileException}.</li>
 * </ul>
 */
public final class JsonlTail {

    static final int BUFFER_SIZE = 1 << 20;

    public record Chunk(List<byte[]> lines, long nextOffset) {}

    private JsonlTail() {}

    public static Chunk read(Path file, long offset, long budgetBytes) throws IOException {
        return read(file, offset, budgetBytes, BUFFER_SIZE);
    }

    static Chunk read(Path file, long offset, long budgetBytes, int bufferSize) throws IOException {
        List<byte[]> lines = new ArrayList<>();
        long nextOffset = offset;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
            byte[] bytes = buffer.array();
            ByteArrayOutputStream carry = new ByteArrayOutputStream();
            long bufferStart = offset;
            int n;
            while ((n = channel.read(buffer.clear(), bufferStart)) > 0) {
                int lineStart = 0;
                for (int i = 0; i < n; i++) {
                    if (bytes[i] != '\n') continue;
                    lines.add(line(carry, bytes, lineStart, i));
                    lineStart = i + 1;
                    nextOffset = bufferStart + lineStart;
                    if (nextOffset - offset >= budgetBytes) return new Chunk(lines, nextOffset);
                }
                carry.write(bytes, lineStart, n - lineStart);
                bufferStart += n;
            }
        }
        return new Chunk(lines, nextOffset);
    }

    /** 앞 버퍼에서 넘어온 조각(carry)과 [from, to)를 이어 한 줄로 만들고 끝 \r을 뗀다. */
    private static byte[] line(ByteArrayOutputStream carry, byte[] bytes, int from, int to) {
        if (carry.size() == 0) {
            int end = to > from && bytes[to - 1] == '\r' ? to - 1 : to;
            return Arrays.copyOfRange(bytes, from, end);
        }
        carry.write(bytes, from, to - from);
        byte[] joined = carry.toByteArray();
        carry.reset();
        int len = joined.length;
        return len > 0 && joined[len - 1] == '\r' ? Arrays.copyOf(joined, len - 1) : joined;
    }
}
