package host.anzo.simon;

import org.apache.mina.core.session.IoSession;

/**
 * Interface for notifying an application about SIMON session events, managed by Dispatcher.
 * @author Anton Lasevich
 */
public interface SimonSessionListener {
    /**
     * Called when the MINA session associated with the SIMON connection is closed.
     * @param session The MINA session that was closed.
     * @param dispatcher The Dispatcher that managed this session.
     */
    void simonSessionClosed(IoSession session, Dispatcher dispatcher);

    /**
     * Called when a session is created
     * @param session The session created.
     * @param dispatcher Dispatcher.
     */
    void simonSessionCreated(IoSession session, Dispatcher dispatcher);

    /**
     * Called when a session is opened
     * @param session The opened session.
     * @param dispatcher Dispatcher.
     */
    void simonSessionOpened(IoSession session, Dispatcher dispatcher);
}