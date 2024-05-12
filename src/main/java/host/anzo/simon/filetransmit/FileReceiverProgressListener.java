/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package host.anzo.simon.filetransmit;

import java.io.File;

/**
 * @author achristian
 */
public interface FileReceiverProgressListener {
	void started(File f, long length);

	void inProgress(File f, long bytesReceived, long length);

	void completed(File f);

	void aborted(File f, Exception e);
}
