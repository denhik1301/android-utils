package me.aap.utils.net.http;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;

import me.aap.utils.async.FutureSupplier;
import me.aap.utils.async.Promise;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.function.Supplier;
import me.aap.utils.io.IoUtils;
import me.aap.utils.log.Log;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;

import static me.aap.utils.async.Completed.completedVoid;
import static me.aap.utils.async.Completed.failed;

/**
 * @author Andrey Pavlenko
 */
public class HttpFileDownloader {
	public static final Pref<Supplier<String>> AGENT = Pref.s("AGENT");
	public static final Pref<Supplier<String>> ETAG = Pref.s("ETAG");
	public static final Pref<Supplier<String>> CHARSET = Pref.s("CHARSET", "UTF-8");
	public static final Pref<Supplier<String>> ENCODING = Pref.s("ENCODING");
	public static final Pref<IntSupplier> RESP_TIMEOUT = Pref.i("RESP_TIMEOUT", 10);
	private StatusListener statusListener;
	private boolean returnExistingOnFail;

	public void setStatusListener(StatusListener statusListener) {
		this.statusListener = statusListener;
	}

	public void setReturnExistingOnFail(boolean returnExistingOnFail) {
		this.returnExistingOnFail = returnExistingOnFail;
	}

	public FutureSupplier<Status> download(String src, File dst, PreferenceStore prefs) {
		try {
			return download(new URL(src), dst, prefs);
		} catch (MalformedURLException ex) {
			return failed(ex);
		}
	}

	public FutureSupplier<Status> download(URL src, File dst, PreferenceStore prefs) {
		Promise<Status> p = new Promise<>();
		StatusListener listener = statusListener;
		Log.d("Downloading ", src, " to ", dst);

		HttpConnection.connect(o -> {
			o.url = src;
			o.responseTimeout = prefs.getIntPref(RESP_TIMEOUT);
			o.userAgent = prefs.getStringPref(AGENT);
			if (dst.isFile()) o.ifNonMatch = prefs.getStringPref(ETAG);
		}, (resp, err) -> {
			if (err != null) {
				completeExceptionally(p, err, new DownloadStatus(src, dst, 0), listener);
				return p;
			}

			DownloadStatus status = new DownloadStatus(src, dst, resp.getContentLength());
			status.setEtag(resp.getEtag());
			status.setCharset(resp.getCharset());
			status.setEncoding(resp.getContentEncoding());
			Log.d("Response received:\n", resp);

			if (resp.getStatusCode() == HttpStatusCode.NOT_MODIFIED) {
				Log.i("File not modified: ", src, ". Returning existing file: ", dst);
				if (listener != null) listener.onSuccess(status);
				p.complete(status);
				return completedVoid();
			}

			try (PreferenceStore.Edit edit = prefs.editPreferenceStore()) {
				edit.setStringPref(ETAG, status.getEtag());
				edit.setStringPref(CHARSET, status.getCharset());
				edit.setStringPref(ENCODING, status.getEncoding());
			}

			File tmp = null;
			try {
				tmp = File.createTempFile(dst.getName(), ".incomplete", dst.getParentFile());
			} catch (IOException ex) {
				Log.e(ex, "Failed to create temporary file");
			}

			File incomplete = (tmp == null) ? new File(dst.getAbsolutePath() + ".incomplete") : tmp;
			return writePayload(resp, incomplete, status, listener).onCompletion((v, fail) -> {
				if (fail != null) {
					completeExceptionally(p, fail, status, listener);
					//noinspection ResultOfMethodCallIgnored
					incomplete.delete();
				} else if (incomplete.renameTo(dst)) {
					if (listener != null) listener.onSuccess(status);
					Log.d("Downloaded ", src, " to ", dst);
					p.complete(status);
				} else {
					completeExceptionally(p, new IOException("Failed to rename file " + incomplete + " to " + dst),
							status, listener);
					//noinspection ResultOfMethodCallIgnored
					incomplete.delete();
				}
			});
		});

		return p;
	}

	private void completeExceptionally(Promise<Status> p, Throwable err, DownloadStatus status, StatusListener listener) {
		Log.e("Failed to download ", status.getSource(), " to ", status.getDestination());

		if (returnExistingOnFail && status.getDestination().isFile()) {
			Log.e(err, "Failed to download: ", status.getSource(), ". Returning existing file: ",
					status.getDestination());
			status.failure = err;
			if (listener != null) listener.onSuccess(status);
			p.complete(status);
		} else {
			if (listener != null) listener.onFailure(status);
			p.completeExceptionally(err);
		}
	}

	private FutureSupplier<?> writePayload(HttpResponse resp, File dst, DownloadStatus status, StatusListener listener) {
		try {
			OutputStream out = new OutputStream() {
				final OutputStream fos = new FileOutputStream(dst);

				@Override
				public void write(int b) throws IOException {
					fos.write(b);
					status.downloadedSize += 1;
					if (listener != null) listener.onProgress(status);
				}

				@Override
				public void write(byte[] b, int off, int len) throws IOException {
					fos.write(b, off, len);
					status.downloadedSize += len;
					if (listener != null) listener.onProgress(status);
				}

				@Override
				public void flush() throws IOException {
					fos.flush();
				}

				@Override
				public void close() throws IOException {
					fos.close();
				}
			};
			return resp.writePayload(out).thenRun(() -> IoUtils.close(out));
		} catch (FileNotFoundException ex) {
			return failed(ex);
		}
	}

	public interface Status {

		URL getSource();

		File getDestination();

		long getTotalSize();

		long getDownloadedSize();

		String getEtag();

		String getCharset();

		String getEncoding();

		Throwable getFailure();
	}

	public interface StatusListener {

		void onProgress(Status status);

		void onSuccess(Status status);

		void onFailure(Status status);
	}

	private static final class DownloadStatus implements Status {
		private final URL source;
		private final File destination;
		private final long totalSize;
		String etag;
		String charset;
		String encoding;
		long downloadedSize;
		Throwable failure;

		public DownloadStatus(URL source, File destination, long totalSize) {
			this.source = source;
			this.destination = destination;
			this.totalSize = totalSize;
		}

		@Override
		public URL getSource() {
			return source;
		}

		@Override
		public File getDestination() {
			return destination;
		}

		@Override
		public long getTotalSize() {
			return totalSize;
		}

		@Override
		public long getDownloadedSize() {
			return downloadedSize;
		}

		@Override
		public String getEtag() {
			return etag;
		}

		public void setEtag(CharSequence etag) {
			if (etag != null) this.etag = etag.toString();
		}

		@Override
		public String getCharset() {
			return charset;
		}

		public void setCharset(CharSequence charset) {
			if (charset != null) this.charset = charset.toString().toUpperCase();
		}

		@Override
		public String getEncoding() {
			return encoding;
		}

		public void setEncoding(CharSequence encoding) {
			if (encoding != null) this.encoding = encoding.toString();
		}

		@Override
		public Throwable getFailure() {
			return failure;
		}

		@Override
		public String toString() {
			return "DownloadStatus {" +
					"\n  source=" + source +
					"\n  destination=" + destination +
					"\n  totalSize=" + totalSize +
					"\n  etag='" + etag + '\'' +
					"\n  charset='" + charset + '\'' +
					"\n  encoding='" + encoding + '\'' +
					"\n  downloadedSize=" + downloadedSize +
					"\n  failure=" + failure +
					"\n}";
		}
	}
}