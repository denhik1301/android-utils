package me.aap.utils.vfs;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.aap.utils.app.App;
import me.aap.utils.app.NetApp;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.net.NetHandler;
import me.aap.utils.net.NetServer;
import me.aap.utils.net.http.HttpConnectionHandler;

import static me.aap.utils.async.Completed.completedNull;
import static me.aap.utils.vfs.VfsHttpHandler.HTTP_PATH;
import static me.aap.utils.vfs.VfsHttpHandler.HTTP_QUERY;

/**
 * @author Andrey Pavlenko
 */
public class VfsManager {

	@NonNull
	private volatile Mounts mounts;
	private volatile NetServer netServer;

	public VfsManager(VirtualFileSystem... fileSystems) {
		this(Arrays.asList(fileSystems));
	}

	public VfsManager(List<VirtualFileSystem> fileSystems) {
		this.mounts = new Mounts(fileSystems);
	}

	public void mount(VirtualFileSystem... fileSystems) {
		mount(Arrays.asList(fileSystems));
	}

	public synchronized void mount(List<VirtualFileSystem> fileSystems) {
		Mounts p = this.mounts;
		List<VirtualFileSystem> list = new ArrayList<>(fileSystems.size() + p.all.size());
		list.addAll(p.all);
		list.addAll(fileSystems);
		this.mounts = new Mounts(list);
	}

	public void umount(VirtualFileSystem... fileSystems) {
		umount(Arrays.asList(fileSystems));
	}

	public synchronized void umount(List<VirtualFileSystem> fileSystems) {
		List<VirtualFileSystem> list = new ArrayList<>(this.mounts.all);
		if (!list.removeAll(fileSystems)) return;
		this.mounts = new Mounts(list);
	}

	public List<VirtualFileSystem> getFileSystems() {
		return mounts.all;
	}

	public List<VirtualFileSystem> getFileSystems(String scheme) {
		List<VirtualFileSystem> fs = mounts.map.get(scheme);
		return (fs == null) ? Collections.emptyList() : fs;
	}

	@NonNull
	public FutureSupplier<VirtualResource> getResource(Uri uri) {
		Mounts mounts = this.mounts;
		List<VirtualFileSystem> list = mounts.map.get(uri.getScheme());

		if ((list != null) && !list.isEmpty()) {
			for (VirtualFileSystem fs : list) {
				if (fs.isSupportedResource(uri)) return fs.getResource(uri);
			}

			return completedNull();
		}

		for (VirtualFileSystem p : mounts.any) {
			if (p.isSupportedResource(uri)) return p.getResource(uri);
		}

		return completedNull();
	}

	@NonNull
	public FutureSupplier<VirtualResource> resolve(@NonNull String pathOrUri, @Nullable VirtualResource relativeTo) {
		Uri u = pathOrUri.contains(":/") || (relativeTo == null) ? Uri.parse(pathOrUri)
				: Uri.parse(relativeTo.getUri().toString() + '/' + pathOrUri);
		return getResource(u);
	}

	public boolean isSupportedScheme(String scheme) {
		return mounts.map.containsKey(scheme);
	}

	public Uri getHttpUri(VirtualResource resource) {
		return getHttpUri(resource.getUri());
	}

	public Uri getHttpUri(Uri resourceUri) {
		int port = getNetServer().getPort();
		String uri = Uri.encode(resourceUri.toString());
		return Uri.parse("http://localhost:" + port + HTTP_PATH + "?" + HTTP_QUERY + uri);
	}

	private NetServer getNetServer() {
		NetServer net = netServer;
		if (net == null) {
			synchronized (this) {
				if ((net = netServer) == null) {
					netServer = net = App.get().execute(this::createHttpServer).getOrThrow();
				}
			}
		}
		return net;
	}

	protected NetServer createHttpServer() {
		NetHandler handler = NetApp.get().getNetHandler();
		HttpConnectionHandler httpHandler = new HttpConnectionHandler();
		VfsHttpHandler vfsHandler = new VfsHttpHandler(this);
		httpHandler.addHandler(HTTP_PATH, (path, method, version) -> vfsHandler);
		return handler.bind(o -> {
			o.host = "localhost";
			o.handler = httpHandler;
		}).getOrThrow();
	}

	private static final class Mounts {
		final List<VirtualFileSystem> all;
		final List<VirtualFileSystem> any;
		final Map<String, List<VirtualFileSystem>> map;

		Mounts(List<VirtualFileSystem> fileSystems) {
			ArrayList<VirtualFileSystem> all = new ArrayList<>();
			ArrayList<VirtualFileSystem> any = new ArrayList<>();
			Map<String, List<VirtualFileSystem>> map = new HashMap<>((int) (fileSystems.size() * 1.5f));

			for (VirtualFileSystem fs : fileSystems) {
				if (all.contains(fs)) continue;

				all.add(fs);
				Set<String> schemes = fs.getProvider().getSupportedSchemes();

				if (schemes.isEmpty()) {
					any.add(fs);
				} else {
					for (String s : schemes) {
						List<VirtualFileSystem> l = map.get(s);

						if (l == null) {
							map.put(s, Collections.singletonList(fs));
						} else {
							List<VirtualFileSystem> newList = new ArrayList<>(l.size() + 1);
							newList.addAll(l);
							newList.add(fs);
							map.put(s, newList);
						}
					}
				}
			}


			all.trimToSize();
			any.trimToSize();

			this.all = all.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(all);
			this.any = all.isEmpty() ? Collections.emptyList() : any;
			this.map = map;
		}
	}
}
