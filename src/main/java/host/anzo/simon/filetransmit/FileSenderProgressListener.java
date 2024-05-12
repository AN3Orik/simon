/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package host.anzo.simon.filetransmit;

import java.io.File;

/**
 * @author achristian
 */
public interface FileSenderProgressListener {
	void started(int id, File f, long length);

	void inProgress(int id, File f, long bytesSent, long length);

	void completed(int id, File f);

	void aborted(int id, File f, Exception ex);
}
