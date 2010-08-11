/*
 * Copyright 2009 Thomas Bocek
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package net.tomp2p.utils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream; // import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.SortedSet;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import net.tomp2p.peers.Number160;
import net.tomp2p.peers.Number480;
import net.tomp2p.peers.PeerAddress;
import net.tomp2p.rpc.DigestInfo;
import net.tomp2p.storage.Storage;

public class Utils
{
	private static final Random random = new Random();

	public static ByteBuffer loadFile(File file) throws IOException
	{
		// at at most 5 sec for file creation... We have the file, but its not
		// created yet. This file creation needs to be blocking..
		for (int i = 0; i < 50 && !file.exists(); i++)
			Utils.sleep(100);
		FileInputStream fis = null;
		FileChannel channel = null;
		try
		{
			fis = new FileInputStream(file);
			channel = fis.getChannel();
			return channel.map(MapMode.READ_ONLY, 0, channel.size());
		}
		finally
		{
			bestEffortclose(channel, fis);
		}
	}

	public static Number160 makeSHAHash(File file)
	{
		FileInputStream fis = null;
		FileChannel channel = null;
		try
		{
			fis = new FileInputStream(file);
			channel = fis.getChannel();
			MessageDigest md = MessageDigest.getInstance("SHA-1");
			for (long offest = 0; offest < channel.size(); offest += 10 * 1024)
			{
				ByteBuffer buffer;
				if (channel.size() - offest < 10 * 1024)
					buffer = channel.map(MapMode.READ_ONLY, offest, (int) channel.size() - offest);
				else
					buffer = channel.map(MapMode.READ_ONLY, offest, 10 * 1024);
				md.update(buffer);
			}
			byte[] digest = md.digest();
			return new Number160(digest);
		}
		catch (FileNotFoundException e)
		{
			e.printStackTrace();
			return Number160.ZERO;
		}
		catch (IOException e)
		{
			e.printStackTrace();
			return Number160.ZERO;
		}
		catch (NoSuchAlgorithmException e)
		{
			e.printStackTrace();
			return Number160.ZERO;
		}
		finally
		{
			bestEffortclose(channel, fis);
		}
	}

	public static Number160 makeSHAHash(String strInput)
	{
		byte[] buffer = strInput.getBytes();
		return makeSHAHash(buffer);
	}

	public static Number160 makeSHAHash(ByteBuffer buffer)
	{
		try
		{
			MessageDigest md = MessageDigest.getInstance("SHA-1");
			md.update(buffer);
			byte[] digest = md.digest();
			return new Number160(digest);
		}
		catch (NoSuchAlgorithmException e)
		{
			e.printStackTrace();
			return new Number160();
		}
	}

	public static Number160 makeSHAHash(byte[] buffer)
	{
		return makeSHAHash(ByteBuffer.wrap(buffer));
	}

	public static Number160 makeSHAHash(byte[] buffer, int offset, int length)
	{
		return makeSHAHash(ByteBuffer.wrap(buffer, offset, length));
	}

	public static Number160 createRandomNodeID()
	{
		// TODO: this hardcoded, bad style
		byte[] me = new byte[20];
		random.nextBytes(me);
		Number160 id = new Number160(me);
		return id;
	}

	public static void sleep(long connectionTimeout)
	{
		try
		{
			Thread.sleep(connectionTimeout);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
	}

	public static byte[] compress(byte[] input)
	{
		// Create the compressor with highest level of compression
		Deflater compressor = new Deflater();
		compressor.setLevel(Deflater.BEST_SPEED);
		// Give the compressor the data to compress
		compressor.setInput(input);
		compressor.finish();
		// Create an expandable byte array to hold the compressed data.
		// You cannot use an array that's the same size as the orginal because
		// there is no guarantee that the compressed data will be smaller than
		// the uncompressed data.
		ByteArrayOutputStream bos = new ByteArrayOutputStream(input.length);
		// Compress the data
		byte[] buf = new byte[1024];
		while (!compressor.finished())
		{
			int count = compressor.deflate(buf);
			bos.write(buf, 0, count);
		}
		try
		{
			bos.close();
		}
		catch (IOException e)
		{
		}
		// Get the compressed data
		return bos.toByteArray();
	}

	public static byte[] uncompress(byte[] compressedData, int offset, int length)
	{
		// Create the decompressor and give it the data to compress
		Inflater decompressor = new Inflater();
		decompressor.setInput(compressedData, offset, length);
		// Create an expandable byte array to hold the decompressed data
		ByteArrayOutputStream bos = new ByteArrayOutputStream(length);
		// Decompress the data
		byte[] buf = new byte[1024];
		while (!decompressor.finished())
		{
			try
			{
				int count = decompressor.inflate(buf);
				bos.write(buf, 0, count);
			}
			catch (DataFormatException e)
			{
				e.printStackTrace();
				throw new RuntimeException(e);
			}
		}
		try
		{
			bos.close();
		}
		catch (IOException e)
		{
			e.printStackTrace();
			throw new RuntimeException(e);
		}
		// Get the decompressed data
		return bos.toByteArray();
	}

	public static byte[] uncompress(byte[] compressedData)
	{
		return uncompress(compressedData, 0, compressedData.length);
	}

	public static byte[] encodeJavaObject(Object attachement) throws IOException
	{
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		ObjectOutputStream oos = new ObjectOutputStream(bos);
		oos.writeObject(attachement);
		// no need to call close of flush since we use ByteArrayOutputStream
		byte[] data = bos.toByteArray();
		return data;
	}

	public static Object decodeJavaObject(byte[] me, int offset, int length)
			throws ClassNotFoundException, IOException
	{
		ByteArrayInputStream bais = new ByteArrayInputStream(me, offset, length);
		ObjectInputStream ois = new ObjectInputStream(bais);
		Object obj = ois.readObject();
		// no need to call close of flush since we use ByteArrayInputStream
		return obj;
	}

	public static void difference(Collection<PeerAddress> newNeighbors,
			Collection<PeerAddress> alreadyAsked, Collection<PeerAddress> result)
	{
		for (Iterator<PeerAddress> iterator = newNeighbors.iterator(); iterator.hasNext();)
		{
			PeerAddress newPeerAddress = iterator.next();
			if (!alreadyAsked.contains(newPeerAddress))
				result.add(newPeerAddress);
		}
	}

	public static void bestEffortclose(Closeable... closables)
	{
		// best effort close;
		for (Closeable closable : closables)
		{
			if (closable != null)
			{
				try
				{
					closable.close();
				}
				catch (IOException e)
				{
					e.printStackTrace();
				}
			}
		}
	}

	// TODO: in Java6, there is navigablemap, which does this much better
	public static PeerAddress pollFirst(SortedSet<PeerAddress> queue)
	{
		try
		{
			if (queue.size() > 0)
			{
				PeerAddress tmp = queue.first();
				queue.remove(tmp);
				return tmp;
			}
			else
				return null;
		}
		catch (NoSuchElementException e)
		{
			e.printStackTrace();
			return null;
		}
	}

	public static final byte[] intToByteArray(int value)
	{
		return new byte[] { (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8),
				(byte) value };
	}

	public static final int byteArrayToInt(byte[] b)
	{
		return (b[0] << 24) + ((b[1] & 0xFF) << 16) + ((b[2] & 0xFF) << 8) + (b[3] & 0xFF);
	}

	public static DigestInfo digest(Storage storage, Number160 locationKey,
			final Number160 domainKey, final Collection<Number160> contentKeys)
	{
		DigestInfo digestInfo;
		if (contentKeys != null)
		{
			Collection<Number480> tmp = new HashSet<Number480>();
			for (Number160 contentKey : contentKeys)
				tmp.add(new Number480(locationKey, domainKey, contentKey));
			digestInfo = storage.digest(tmp);
		}
		else
		{
			digestInfo = storage.digest(new Number480(locationKey, domainKey, Number160.ZERO),
					new Number480(locationKey, domainKey, Number160.MAX_VALUE));
		}
		return digestInfo;
	}
}