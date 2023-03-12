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
package app.revanced.patcher.util

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.SeekableByteChannel

/**
 * [SeekableByteChannel] implementation backed by an auto-resizing byte array; thread-safe. Can hold a maxiumum of
 * [Integer.MAX_VALUE] bytes.
 *
 * @author [Andrew Lee Rubinger](mailto:alr@jboss.org)
 */
open class InMemoryChannel : SeekableByteChannel {
    /**
     * Current position; guarded by "this"
     */
    internal var position = 0

    /**
     * Whether or not this [SeekableByteChannel] is open; volatile instead of sync is acceptable because this
     * field participates in no compound computations or invariants with other instance members.
     */
    @Volatile
    internal var open = true

    /**
     * Internal buffer for contents; guarded by "this"
     */
    internal var contents: ByteArray

    /**
     * Creates a new instance with 0 size and 0 position, and open.
     */
    init {

        // Set fields
        synchronized(this) {
            position = 0
            contents = ByteArray(0)
        }
    }

    /**
     * {@inheritDoc}
     *
     * @see java.nio.channels.Channel.isOpen
     */
    override fun isOpen(): Boolean {
        return open
    }

    /**
     * {@inheritDoc}
     *
     * @see java.nio.channels.Channel.close
     */
    @Throws(IOException::class)
    override fun close() {
        open = false
    }

    /**
     * {@inheritDoc}
     *
     * @see java.nio.channels.SeekableByteChannel.read
     */
    @Throws(IOException::class)
    override fun read(destination: ByteBuffer): Int {

        // Precondition checks
        checkClosed()
        requireNotNull(destination) { "Destination buffer must be supplied" }

        // Init
        val spaceInBuffer = destination.remaining()
        val numBytesRemainingInContent: Int
        val numBytesToRead: Int
        val bytesToCopy: ByteArray

        // Sync up before getting at shared mutable state
        synchronized(this) {
            numBytesRemainingInContent = contents.size - position

            // Set position was greater than the size? Just return.
            if (numBytesRemainingInContent < 0) {
                return 0
            }

            // We'll read in either the number of bytes remaining in content or the amount of space in the buffer,
            // whichever is smaller
            numBytesToRead =
                if (numBytesRemainingInContent >= spaceInBuffer) spaceInBuffer else numBytesRemainingInContent

            // Copy a sub-array of the bytes we'll put into the buffer
            bytesToCopy = ByteArray(numBytesToRead)
            System.arraycopy(contents, position, bytesToCopy, 0, numBytesToRead)

            // Set the new position
            position += numBytesToRead
        }

        // Put the contents into the buffer
        destination.put(bytesToCopy)

        // Return the number of bytes read
        return numBytesToRead
    }

    /**
     * {@inheritDoc}
     *
     * @see java.nio.channels.SeekableByteChannel.write
     */
    @Throws(IOException::class)
    override fun write(source: ByteBuffer): Int {

        // Precondition checks
        checkClosed()
        requireNotNull(source) { "Source buffer must be supplied" }

        // Put the bytes to be written into a byte[]
        val totalBytes = source.remaining()
        val readContents = ByteArray(totalBytes)
        source[readContents]

        // Sync up, we're gonna access shared mutable state
        synchronized(this) {


            // Append the read contents to our internal contents
            contents = concat(contents, readContents, position)

            // Increment the position of this channel
            position += totalBytes
        }

        // Return the number of bytes read
        return totalBytes
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
    private fun concat(input1: ByteArray?, input2: ByteArray?, position: Int): ByteArray {
        // Preconition checks
        assert(input1 != null) { "Input 1 must be specified" }
        assert(input2 != null) { "Input 2 must be specified" }
        assert(position >= 0) { "Position must be 0 or higher" }
        // Allocate a new array of enough space (either current size or position + input2.length, whichever is greater)
        val newSize = if (position < input1!!.size) input1.size + input2!!.size else position + input2!!.size
        val merged = ByteArray(newSize)
        // Copy in the contents of input 1 with 0 offset
        System.arraycopy(input1, 0, merged, 0, input1.size)
        // Copy in the contents of input2 with offset the length of input 1
        System.arraycopy(input2, 0, merged, position, input2.size)
        return merged
    }

    /**
     * {@inheritDoc}
     *
     * @see java.nio.channels.SeekableByteChannel.position
     */
    @Throws(IOException::class)
    override fun position(): Long {
        synchronized(this) { return position.toLong() }
    }

    /**
     * {@inheritDoc}
     *
     * @see java.nio.channels.SeekableByteChannel.position
     */
    @Throws(IOException::class)
    override fun position(newPosition: Long): SeekableByteChannel {
        // Precondition checks
        require(!(newPosition > Int.MAX_VALUE || newPosition < 0)) { "Valid position for this channel is between 0 and " + Int.MAX_VALUE }
        synchronized(this) { position = newPosition.toInt() }
        return this
    }

    /**
     * {@inheritDoc}
     *
     * @see java.nio.channels.SeekableByteChannel.size
     */
    @Throws(IOException::class)
    override fun size(): Long {
        synchronized(this) { return contents.size.toLong() }
    }

    /**
     * {@inheritDoc}
     *
     * @see java.nio.channels.SeekableByteChannel.truncate
     */
    @Throws(IOException::class)
    override fun truncate(size: Long): SeekableByteChannel {

        // Precondition checks
        require(!(size < 0 || size > Int.MAX_VALUE)) { "This implementation permits a size of 0 to " + Int.MAX_VALUE + " inclusive" }

        // Sync up for mucking w/ shared mutable state
        synchronized(this) {
            val newSize = size.toInt()
            val currentSize = size().toInt()

            // If the current position is greater than the given size, set to the given size (by API spec)
            if (position > newSize) {
                position = newSize
            }

            // If we've been given a size smaller than we currently are
            if (currentSize > newSize) {
                // Make new array
                val newContents = ByteArray(newSize)
                // Copy in the contents up to the new size
                System.arraycopy(contents, 0, newContents, 0, newSize)
                // Set the new array as our contents
                contents = newContents
            }
            // If we've been given a size greater than we are
            if (newSize > currentSize) {
                // Reset the position only
                position = newSize
            }
        }

        // Return this reference
        return this
    }

    /**
     * Throws a [ClosedChannelException] if this [SeekableByteChannel] is closed.
     *
     * @throws ClosedChannelException
     */
    @Throws(ClosedChannelException::class)
    private fun checkClosed() {
        if (!this.isOpen) {
            throw ClosedChannelException()
        }
    }
}