package me.aap.utils.concurrent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import me.aap.utils.misc.Assert;
import me.aap.utils.text.SharedTextBuilder;

/**
 * @author Andrey Pavlenko
 */
public class PooledThread extends Thread {
	private SharedTextBuilder sb;

	public PooledThread() {
	}

	public PooledThread(@Nullable Runnable target) {
		super(target);
	}

	public PooledThread(@Nullable Runnable target, @NonNull String name) {
		super(target, name);
	}

	public SharedTextBuilder getSharedTextBuilder() {
		Assert.assertSame(this, Thread.currentThread());
		return (sb != null) ? sb : (sb = SharedTextBuilder.create(this));
	}
}