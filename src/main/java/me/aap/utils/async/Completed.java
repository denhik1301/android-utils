package me.aap.utils.async;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import me.aap.utils.BuildConfig;
import me.aap.utils.function.Function;
import me.aap.utils.function.ProgressiveResultConsumer;
import me.aap.utils.function.Supplier;

import static me.aap.utils.async.CompletableSupplier.Cancelled.CANCELLED;
import static me.aap.utils.function.ResultConsumer.Cancel.isCancellation;
import static me.aap.utils.function.ResultConsumer.Cancel.newCancellation;
import static me.aap.utils.misc.Assert.assertTrue;

/**
 * @author Andrey Pavlenko
 */
public abstract class Completed<T> implements FutureSupplier<T> {

	@SuppressWarnings("unchecked")
	public static <T> FutureSupplier<T> cancelled() {
		return Cancelled.instance;
	}

	public static <T> FutureSupplier<T> failed(Throwable fail) {
		return !isCancellation(fail) ? new Failed<>(fail) : cancelled();
	}

	public static <T> FutureSupplier<T> completed(T result) {
		return (result != null) ? new Result<>(result) : completedNull();
	}

	public static <T> FutureSupplier<T> completed(T result, Throwable fail) {
		if (fail != null) {
			if (isCancellation(fail)) return cancelled();
			else return failed(fail);
		} else {
			return completed(result);
		}
	}

	public static <T> FutureSupplier<T> completed(FutureSupplier<T> done) {
		if (done instanceof Completed) return done;
		assertTrue(done.isDone());

		if (done.isFailed()) {
			return done.isCancelled() ? cancelled() : failed(done.getFailure());
		} else {
			return completed(done.getOrThrow());
		}
	}

	public static <T> FutureSupplier<T> completedOrNull(FutureSupplier<T> done) {
		return completedOr(done, fail -> null);
	}

	public static <T> FutureSupplier<T> completedOr(FutureSupplier<T> done,
																									Function<FutureSupplier<T>, FutureSupplier<T>> onFail) {
		assertTrue(done.isDone());
		if (done.isFailed()) return onFail.apply(done);
		if (done instanceof Completed) return done;
		else return completed(done.getOrThrow());
	}

	public static <T> FutureSupplier<List<T>> completed(List<T> result) {
		return (result != Collections.EMPTY_LIST) ? new Result<>(result) : completedEmptyList();
	}

	public static FutureSupplier<Integer> completed(int result) {
		return (result == 0) ? ZeroInt.instance : new Result<>(result);
	}

	public static FutureSupplier<Long> completed(long result) {
		return (result == 0L) ? ZeroLong.instance : new Result<>(result);
	}

	public static FutureSupplier<Boolean> completed(boolean result) {
		return result ? True.instance : False.instance;
	}

	@SuppressWarnings("unchecked")
	public static <T> FutureSupplier<T> completedNull() {
		return Null.instance;
	}

	public static boolean isCompletedNull(FutureSupplier<?> f) {
		return f == Null.instance;
	}

	@SuppressWarnings("unchecked")
	public static FutureSupplier<Void> completedVoid() {
		return Null.instance;
	}

	@SuppressWarnings("unchecked")
	public static <T> FutureSupplier<List<T>> completedEmptyList() {
		return EmptyList.instance;
	}

	@SuppressWarnings("unchecked")
	public static <K, V> FutureSupplier<Map<K, V>> completedEmptyMap() {
		return EmptyMap.instance;
	}

	@Override
	public boolean cancel(boolean mayInterruptIfRunning) {
		return false;
	}

	@Override
	public boolean isDone() {
		return true;
	}

	@NonNull
	@Override
	public String toString() {
		return String.valueOf(isFailed() ? getFailure() : get(null));
	}

	private static abstract class Successful<T> extends Completed<T> {

		@Nullable
		@Override
		public final Throwable getFailure() {
			return null;
		}

		@Override
		public final boolean isFailed() {
			return false;
		}

		@Override
		public final boolean isCancelled() {
			return false;
		}

		@Override
		public abstract T get();

		@Override
		public final T get(long timeout, @NonNull TimeUnit unit) {
			return get();
		}

		@Override
		public final T get(@Nullable Supplier<? extends T> onError) {
			return get();
		}

		@Override
		public final T get(@Nullable Supplier<? extends T> onError, long timeout, @NonNull TimeUnit unit) {
			return get();
		}

		@Override
		public final FutureSupplier<T> addConsumer(@NonNull ProgressiveResultConsumer<? super T> c) {
			c.accept(get(), null);
			return this;
		}
	}

	private static final class Failed<T> extends Completed<T> {
		private final Throwable fail;

		Failed(Throwable fail) {
			if (BuildConfig.DEBUG) Log.d(getClass().getName(), fail.getMessage(), fail);
			this.fail = fail;
		}

		@NonNull
		@Override
		public Throwable getFailure() {
			return fail;
		}

		@Override
		public boolean isCancelled() {
			return false;
		}

		@Override
		public boolean isFailed() {
			return true;
		}

		@Override
		public T get() throws ExecutionException {
			throw new ExecutionException(fail);
		}

		@Override
		public T get(long timeout, @NonNull TimeUnit unit) throws ExecutionException {
			throw new ExecutionException(fail);
		}

		@Override
		public FutureSupplier<T> addConsumer(@NonNull ProgressiveResultConsumer<? super T> c) {
			c.accept(null, fail);
			return this;
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static final class Cancelled extends Completed {
		static final Cancelled instance = new Cancelled();

		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			return false;
		}

		@Override
		public boolean isCancelled() {
			return true;
		}

		@Override
		public boolean isFailed() {
			return true;
		}

		@Override
		public boolean isDone() {
			return true;
		}

		@Override
		public Object get() {
			throw new CancellationException();
		}

		@Override
		public Object get(long timeout, @NonNull TimeUnit unit) {
			throw new CancellationException();
		}

		@NonNull
		@Override
		public Throwable getFailure() {
			return CANCELLED.fail;
		}

		@Override
		public FutureSupplier addConsumer(@NonNull ProgressiveResultConsumer c) {
			c.accept(null, newCancellation());
			return this;
		}
	}

	private static final class Result<T> extends Successful<T> {
		private final T result;

		Result(T result) {
			this.result = result;
		}

		@Override
		public T get() {
			return result;
		}
	}

	@SuppressWarnings("rawtypes")
	private static final class Null extends Successful {
		static final Null instance = new Null();

		@Override
		public Object get() {
			return null;
		}
	}

	private static final class True extends Successful<Boolean> {
		static final True instance = new True();

		@Override
		public Boolean get() {
			return Boolean.TRUE;
		}
	}

	private static final class False extends Successful<Boolean> {
		static final False instance = new False();

		@Override
		public Boolean get() {
			return Boolean.FALSE;
		}
	}

	private static final class ZeroInt extends Successful<Integer> {
		static final ZeroInt instance = new ZeroInt();
		private static final Integer ZERO = 0;

		@Override
		public Integer get() {
			return ZERO;
		}
	}

	private static final class ZeroLong extends Successful<Long> {
		static final ZeroLong instance = new ZeroLong();
		private static final Long ZERO = 0L;

		@Override
		public Long get() {
			return ZERO;
		}
	}

	@SuppressWarnings("rawtypes")
	private static final class EmptyList extends Successful {
		static final EmptyList instance = new EmptyList();

		@Override
		public List get() {
			return Collections.EMPTY_LIST;
		}
	}

	@SuppressWarnings("rawtypes")
	private static final class EmptyMap extends Successful {
		static final EmptyMap instance = new EmptyMap();

		@Override
		public Map get() {
			return Collections.EMPTY_MAP;
		}
	}
}