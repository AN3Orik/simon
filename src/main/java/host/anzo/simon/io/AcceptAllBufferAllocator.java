package host.anzo.simon.io;

import lombok.extern.slf4j.Slf4j;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.buffer.SimpleBufferAllocator;
import org.apache.mina.core.buffer.matcher.ClassNameMatcher;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;

/**
 * @author Anton Lasevich
 */
@Slf4j
public class AcceptAllBufferAllocator extends SimpleBufferAllocator {
    private static final List<ClassNameMatcher> allowMatcher = Collections.singletonList(className -> true);

    public AcceptAllBufferAllocator() {
        super();
    }

    private IoBuffer applyAcceptAll(IoBuffer buffer) {
        if (buffer != null) {
            buffer.setMatchers(allowMatcher);
        }
        return buffer;
    }

    @Override
    public IoBuffer allocate(int capacity, boolean direct) {
        final IoBuffer buffer = super.allocate(capacity, direct);
        return applyAcceptAll(buffer);
    }

    @Override
    public IoBuffer wrap(ByteBuffer nioBuffer) {
        final IoBuffer buffer = super.wrap(nioBuffer);
        return applyAcceptAll(buffer);
    }
}