package app.revanced.patcher.util
/*
 * JBoss, Home of Professional Open Source
 * Copyright 2012, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.SeekableByteChannel

/**
 * {@link SeekableByteChannel} implementation backed by an auto-resizing byte array; thread-safe. Can hold a maxiumum of
 * {@link Integer#MAX_VALUE} bytes.
 *
 * @author <a href="mailto:alr@jboss.org">Andrew Lee Rubinger</a>
 */
open class InMemoryChannel : SeekableByteChannel {

    /**
     * Current position; guarded by "this"
     */
    internal var position = 0

    /**
     * Whether or not this {@link SeekableByteChannel} is open; volatile instead of sync is acceptable because this
     * field participates in no compound computations or invariants with other instance members.
     */
    private var open = true

    /**
     * Internal buffer for contents; guarded by "this"
     */
    internal var contents = ByteArray(0)

    /**
     * {@inheritDoc}
     *
     * @see java.nio.channels.Channel#isOpen()
     */
    override fun isOpen() = open

    /**
     * {@inheritDoc}
     *
     * @see java.nio.channels.Channel#close()
     */
    override fun close() {
        open = false
    }

    /**
     * {@inheritDoc}
     *
     * @see java.nio.channels.SeekableByteChannel#read(java.nio.ByteBuffer)
     */
    override fun read(destination: ByteBuffer): Int {

        // Precondition checks
        checkClosed();

        // Init
        val spaceInBuffer = destination.remaining()
        var numBytesRemainingInContent = 0
        var numBytesToRead = 0;
        var bytesToCopy = byteArrayOf();

        // Sync up before getting at shared mutable state
        synchronized (this) {
            numBytesRemainingInContent = this.contents.size - this.position;

            // Set position was greater than the size? Just return.
            if (numBytesRemainingInContent < 0) {
                return 0;
            }

            // We'll read in either the number of bytes remaining in content or the amount of space in the buffer,
            // whichever is smaller
            numBytesToRead = if (numBytesRemainingInContent >= spaceInBuffer) spaceInBuffer else numBytesRemainingInContent;

            // Copy a sub-array of the bytes we'll put into the buffer
            bytesToCopy = ByteArray(numBytesToRead);
            System.arraycopy(this.contents, this.position, bytesToCopy, 0, numBytesToRead);

            // Set the new position
            this.position += numBytesToRead;
        }

        // Put the contents into the buffer
        destination.put(bytesToCopy);

        // Return the number of bytes read
        return numBytesToRead;
    }

    /**
     * {@inheritDoc}
     *
     * @see java.nio.channels.SeekableByteChannel#write(java.nio.ByteBuffer)
     */
    override fun write(source: ByteBuffer): Int {

        // Precondition checks
        checkClosed();
        // Put the bytes to be written into a byte[]
        val totalBytes = source.remaining();
        val readContents = ByteArray(totalBytes);
        source.get(readContents);

        // Sync up, we're gonna access shared mutable state
        synchronized (this) {

            // Append the read contents to our internal contents
            contents = concat(contents, readContents, position);

            // Increment the position of this channel
            position += totalBytes;

        }

        // Return the number of bytes read
        return totalBytes;
    }

    /**
     * Creates a new array which is the concatenated result of the two inputs, at the designated position (to be filled
     * with 0x00) in the case of a gap).
     *
     * @param input1
     * @param input2
     * @param position
     * @return
     */
    private fun concat(input1: ByteArray, input2: ByteArray, position: Int): ByteArray {
        // Preconition checks
        assert(input1 != null) { "Input 1 must be specified" }
        assert(input2 != null) { "Input 2 must be specified" }
        assert(position >= 0) { "Position must be 0 or higher" }
        // Allocate a new array of enough space (either current size or position + input2.length, whichever is greater)
        val newSize = if (position < input1.size) input1.size + input2.size else position + input2.size
        val merged = ByteArray(newSize)
        // Copy in the contents of input 1 with 0 offset
        System.arraycopy(input1, 0, merged, 0, input1.size);
        // Copy in the contents of input2 with offset the length of input 1
        System.arraycopy(input2, 0, merged, position, input2.size);
        return merged;
    }

    /**
     * {@inheritDoc}
     *
     * @see java.nio.channels.SeekableByteChannel#position()
     */
    override fun position(): Long {
        synchronized (this) {
            return position.toLong()
        }
    }

    /**
     * {@inheritDoc}
     *
     * @see java.nio.channels.SeekableByteChannel#position(long)
     */
    override fun position(newPosition: Long): InMemoryChannel {
        // Precondition checks
        if (newPosition > Integer.MAX_VALUE || newPosition < 0) {
            throw IllegalArgumentException("Valid position for this channel is between 0 and " + Integer.MAX_VALUE);
        }
        synchronized (this) {
            position = newPosition.toInt()
        }
        return this;
    }

    /**
     * {@inheritDoc}
     *
     * @see java.nio.channels.SeekableByteChannel#size()
     */
    override fun size(): Long {
        synchronized (this) {
            return this.contents.size.toLong();
        }
    }

    /**
     * {@inheritDoc}
     *
     * @see java.nio.channels.SeekableByteChannel#truncate(long)
     */
    override fun truncate(size: Long): InMemoryChannel {

        // Precondition checks
        if (size < 0 || size > Integer.MAX_VALUE) {
            throw IllegalArgumentException("This implementation permits a size of 0 to " + Integer.MAX_VALUE
                    + " inclusive");
        }

        // Sync up for mucking w/ shared mutable state
        synchronized (this) {

            val newSize = size.toInt()
            val currentSize = size().toInt();

            // If the current position is greater than the given size, set to the given size (by API spec)
            if (this.position > newSize) {
                this.position = newSize;
            }

            // If we've been given a size smaller than we currently are
            if (currentSize > newSize) {
                // Make new array
                val newContents = ByteArray(newSize)
                // Copy in the contents up to the new size
                System.arraycopy(this.contents, 0, newContents, 0, newSize);
                // Set the new array as our contents
                this.contents = newContents;
            }
            // If we've been given a size greater than we are
            if (newSize > currentSize) {
                // Reset the position only
                this.position = newSize;
            }
        }

        // Return this reference
        return this;
    }

    /**
     * Throws a {@link ClosedChannelException} if this {@link SeekableByteChannel} is closed.
     *
     * @throws ClosedChannelException
     */
    private fun checkClosed() {
        if (!this.isOpen) {
            throw ClosedChannelException();
        }
    }
}