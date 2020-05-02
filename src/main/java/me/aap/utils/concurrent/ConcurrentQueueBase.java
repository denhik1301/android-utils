package me.aap.utils.concurrent;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.AbstractQueue;
import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * @author Andrey Pavlenko
 */
@SuppressWarnings("rawtypes")
public abstract class ConcurrentQueueBase<E, N extends ConcurrentQueueBase.Node<E>> extends AbstractQueue<E> {
	private static final AtomicReferenceFieldUpdater<ConcurrentQueueBase, Node> HEAD = AtomicReferenceFieldUpdater.newUpdater(ConcurrentQueueBase.class, Node.class, "head");
	private static final AtomicReferenceFieldUpdater<ConcurrentQueueBase, Node> TAIL = AtomicReferenceFieldUpdater.newUpdater(ConcurrentQueueBase.class, Node.class, "tail");
	@Keep
	@SuppressWarnings("unused")
	private volatile Node head;
	@Keep
	@SuppressWarnings("unused")
	private volatile Node tail;

	protected ConcurrentQueueBase() {
		head = tail = newInitNode();
	}

	protected abstract N newNode(E e);

	protected Node<?> newInitNode() {
		return new BasicNode<E>(null);
	}

	@Override
	public boolean offer(E e) {
		offerNode(newNode(e));
		return true;
	}

	@SuppressWarnings("unchecked")
	protected void offerNode(N node) {
		for (Node t = tail, n = t.getNext(); ; t = tail, n = t.getNext()) {
			if (n == null) {
				if (t.compareAndSetNext(null, node)) {
					TAIL.compareAndSet(this, t, node);
					return;
				}
			} else {
				TAIL.compareAndSet(this, t, n);
			}
		}
	}

	@Nullable
	@Override
	public E poll() {
		N n = pollNode();
		if (n == null) return null;

		E e = n.getValue();
		n.clearValue();
		return e;
	}

	@SuppressWarnings("unchecked")
	protected N pollNode() {
		for (Node h = head, t = tail, n = h.getNext(); ; h = head, t = tail, n = h.getNext()) {
			if (h == t) {
				if (n == null) {
					return null;
				} else {
					TAIL.compareAndSet(this, t, n);
				}
			} else if (HEAD.compareAndSet(this, h, n)) {
				return (N) n;
			}
		}
	}

	@Nullable
	@Override
	public E peek() {
		N n = peekNode();
		return (n != null) ? n.getValue() : null;
	}

	@SuppressWarnings("unchecked")
	protected N peekNode() {
		for (Node h = head, t = tail, n = h.getNext(); ; h = head, t = tail, n = h.getNext()) {
			if (head == tail) {
				if (n == null) {
					return null;
				} else {
					TAIL.compareAndSet(this, t, n);
				}
			} else {
				return (N) n;
			}
		}
	}

	@NonNull
	@Override
	public Iterator<E> iterator() {
		N node = peekNode();
		if (node == null) return Collections.emptyIterator();

		return new Iterator<E>() {
			N current = node;

			@Override
			public boolean hasNext() {
				return current != null;
			}

			@SuppressWarnings("unchecked")
			@Override
			public E next() {
				N c = current;
				if (c == null) throw new NoSuchElementException();
				current = (N) c.getNext();
				return c.getValue();
			}
		};
	}

	protected Iterator<N> nodeIterator() {
		N node = peekNode();
		if (node == null) return Collections.emptyIterator();

		return new Iterator<N>() {
			N current = node;

			@Override
			public boolean hasNext() {
				return current != null;
			}

			@SuppressWarnings("unchecked")
			@Override
			public N next() {
				N c = current;
				if (c == null) throw new NoSuchElementException();
				current = (N) c.getNext();
				return (N) c;
			}
		};
	}

	@Override
	public int size() {
		Node node = peekNode();
		if (node == null) return 0;

		int size = 0;

		do {
			size++;
			node = node.getNext();
		} while (node != null);

		return size;
	}

	@Override
	public boolean isEmpty() {
		return peekNode() == null;
	}

	@Override
	public void clear() {
		head = tail = newInitNode();
	}

	public interface Node<E> {

		Node<E> getNext();

		boolean compareAndSetNext(Node<E> expect, Node<E> update);

		E getValue();

		default void clearValue() {
		}
	}

	public static class BasicNode<E> implements Node<E> {
		private static final AtomicReferenceFieldUpdater<BasicNode, Node> NEXT =
				AtomicReferenceFieldUpdater.newUpdater(BasicNode.class, Node.class, "next");
		@Keep
		@SuppressWarnings("unused")
		private volatile Node<E> next;
		private final E value;

		public BasicNode(E value) {
			this.value = value;
		}

		@Override
		public Node<E> getNext() {
			return next;
		}

		@Override
		public boolean compareAndSetNext(Node<E> expect, Node<E> update) {
			return NEXT.compareAndSet(this, expect, update);
		}

		@Override
		public E getValue() {
			return value;
		}
	}
}
