package me.aap.utils.vfs;

import java.nio.ByteBuffer;

import me.aap.utils.async.FutureSupplier;
import me.aap.utils.net.NetChannel;
import me.aap.utils.net.http.HttpError;
import me.aap.utils.net.http.HttpError.Forbidden;
import me.aap.utils.net.http.HttpError.NotFound;
import me.aap.utils.net.http.HttpError.ServiceUnavailable;
import me.aap.utils.net.http.HttpMethod;
import me.aap.utils.net.http.HttpRequest;
import me.aap.utils.net.http.HttpRequestHandler;
import me.aap.utils.net.http.HttpResponseBuilder;
import me.aap.utils.net.http.HttpVersion;
import me.aap.utils.net.http.Range;
import me.aap.utils.resource.Rid;

import static me.aap.utils.async.Completed.completedVoid;
import static me.aap.utils.net.http.HttpHeader.ACCEPT_RANGES;
import static me.aap.utils.net.http.HttpHeader.CONNECTION;
import static me.aap.utils.net.http.HttpHeader.CONTENT_LENGTH;
import static me.aap.utils.net.http.HttpHeader.CONTENT_RANGE;
import static me.aap.utils.net.http.HttpResponseBuilder.supplier;
import static me.aap.utils.net.http.HttpVersion.HTTP_1_1;

/**
 * @author Andrey Pavlenko
 */
public class VfsHttpHandler implements HttpRequestHandler {
	public static final String HTTP_PATH = "/vfs";
	public static final String HTTP_QUERY = "resource=";
	private final VfsManager mgr;

	public VfsHttpHandler(VfsManager mgr) {
		this.mgr = mgr;
	}

	@Override
	public FutureSupplier<Void> handleRequest(HttpRequest req) {
		NetChannel channel = req.getChannel();
		Rid rid = getRid(req);

		if (rid == null) {
			return NotFound.instance.write(channel);
		}

		Range range = req.getRange();
		HttpMethod method = req.getMethod();
		HttpVersion version = req.getVersion();
		boolean close = req.isConnectionClose();

		mgr.getResource(rid).onCompletion((result, fail) -> {
			if (fail != null) {
				NotFound.instance.write(channel);
				return;
			}

			if (!(result instanceof VirtualFile)) {
				Forbidden.instance.write(channel);
				return;
			}

			VirtualFile file = (VirtualFile) result;

			file.getLength().onCompletion((len, err) -> {
				if (err != null) {
					ServiceUnavailable.instance.write(channel);
					return;
				}

				if ((len >= 0) && (range != null)) {
					range.align(len);

					if (!range.isSatisfiable(len)) {
						HttpError.RangeNotSatisfiable.instance.write(channel);
						return;
					}
				}

				FutureSupplier<Void> reply;

				if (method == HttpMethod.HEAD) {
					reply = channel.write(supplier(b -> buildResponse(b, version, len, range, close)));
				} else if (range != null) {
					long start = range.getStart();
					reply = file.transferTo(channel, start, range.getEnd() - start + 1,
							supplier(b -> buildResponse(b, version, len, range, close)));
				} else {
					reply = file.transferTo(channel, 0, len,
							supplier(b -> buildResponse(b, version, len, null, close)));
				}

				reply.onCompletion((r, f) -> {
					if (f != null) ServiceUnavailable.instance.write(channel);
					else if (close) channel.close();
				});
			});
		});

		return completedVoid();
	}

	protected Rid getRid(HttpRequest req) {
		CharSequence q = req.getQuery();
		if ((q == null) || (q.length() <= HTTP_QUERY.length())) {
			return null;
		}
		return Rid.create(Rid.decode(q.subSequence(HTTP_QUERY.length(), q.length())));
	}

	protected ByteBuffer[] buildResponse(HttpResponseBuilder b, HttpVersion version, long len, Range range, boolean close) {
		if (len < 0) {
			b.setStatusOk(version);
			if (close) b.addHeader(CONNECTION);
			return b.build();
		}

		long contentLen = len;

		if (range != null) {
			contentLen = range.getEnd() - range.getStart() + 1;
			b.setStatusPartial(version);
			b.addHeader(CONTENT_RANGE, "bytes " + range.getStart() + '-' + range.getEnd() + '/' + len);
		} else {
			b.setStatusOk(version);
		}

		b.addHeader(ACCEPT_RANGES);
		b.addHeader(CONTENT_LENGTH, contentLen);
		if (close) b.addHeader(CONNECTION);
		else if (version != HTTP_1_1) b.addHeader(CONNECTION, "Keep-Alive");
		return b.build();
	}
}
