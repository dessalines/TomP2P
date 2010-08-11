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
package net.tomp2p.peers;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import net.tomp2p.utils.CacheMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 
 * This routing implementation uses is based on Kademlia. However, many changes
 * have been applied to make it faster and more flexible. This class is
 * partially thread-safe.
 * 
 * @author Thomas Bocek
 * 
 */
public class PeerMapKadImpl implements PeerMap
{
	final private static Logger logger = LoggerFactory.getLogger(PeerMapKadImpl.class);
	// each distance bit has its own bag, which can grow.
	final private int bagSize;
	// the maximum total peers in all bags
	final private int maxPeers;
	// the id of this node
	final private Number160 self;
	// go for variable bag size. Much more performance for small networks
	final private List<Map<Number160, PeerAddress>> peerMap = new ArrayList<Map<Number160, PeerAddress>>();
	// this is used to find bags that are oversized. We could also iterate, but
	// this is faster to keep track of them.
	final private Map<Integer, Object> overSizeBagIndex = new ConcurrentHashMap<Integer, Object>(
			16, 0.75f, 1);
	// In this bag, peers are temporarily stored that have been removed in order
	// to not reappear again.
	final private Map<PeerAddress, Log> peerOfflineLogs;
	// the timeout of the removed peers to stay in the removedPeerCache
	final private int cacheTimeout;
	final private int maxFail;
	// counts the number of peers, this is faster than iterating and counting.
	final private AtomicInteger peerCount = new AtomicInteger();
	// stores listeners that will be notified if a peer gets removed or added
	final private List<PeerMapChangeListener> peerMapChangeListeners = new ArrayList<PeerMapChangeListener>();
	final private List<PeerOfflineListener> peerListeners = new ArrayList<PeerOfflineListener>();
	final private int[] maintenanceTimeoutsSeconds;
	final private Map<PeerAddress, Long> maintenance = new LinkedHashMap<PeerAddress, Long>();
	final private Collection<InetAddress> filteredAddresses = Collections
			.synchronizedSet(new HashSet<InetAddress>());
	final private PeerMapStat peerMapStat;
	class Log
	{
		private int counter;
		private long lastOffline;

		private void inc()
		{
			counter++;
			lastOffline = System.currentTimeMillis();
		}

		private void set(int counter)
		{
			this.counter = counter;
			lastOffline = System.currentTimeMillis();
		}

		private int getCounter()
		{
			return counter;
		}

		private long getLastOffline()
		{
			return lastOffline;
		}
	}

	/**
	 * Creates a bag of peers
	 * 
	 * @param self The id of this peer
	 * @param bagSize The size of a bag. The original Kademlia suggests 20, but
	 *        we can go much lower, as we dont have a fixed limit for a bag. The
	 *        bagSize is a suggestion and if maxpeers has not been reached, the
	 *        peer is added even though it exceeds the bag limit.
	 * @param cacheSize The size of the cache of removed peers
	 * @param cacheTimeout The time that a removed peer will be in the cache in
	 *        milliseconds.
	 * @param peerMapChangeListener Listeners that will be called if a peer is
	 *        added or removed.
	 */
	public PeerMapKadImpl(Number160 self, int bagSize, int cacheSize, int cacheTimeout,
			int maxFail, int[] maintenanceTimeoutsSeconds)
	{
		if (self == null || self.isZero())
			throw new IllegalArgumentException("Zero or null are not a valid IDs");
		this.self = self;
		this.peerMapStat = new PeerMapStat();
		this.bagSize = bagSize;
		this.maxPeers = bagSize * Number160.BITS;
		this.cacheTimeout = cacheTimeout;
		this.maxFail = maxFail;
		this.maintenanceTimeoutsSeconds = maintenanceTimeoutsSeconds;
		this.peerOfflineLogs = new CacheMap<PeerAddress, Log>(cacheSize);
		for (int i = 0; i < Number160.BITS; i++)
		{
			// I made some experiments here and concurrent sets are not
			// necessary, as we divide similar to segments aNonBlockingHashSets
			// in a
			// concurrent map. In a full network, we have 160 segments, for
			// smaller we see around 3-4 segments, growing with the number of
			// peers. bags closer to 0 will see more read than write, and bags
			// closer to 160 will see more writes than reads.
			peerMap
					.add(Collections
							.<Number160, PeerAddress> synchronizedMap(new HashMap<Number160, PeerAddress>()));
		}
	}

	@Override
	public void addPeerMapChangeListener(PeerMapChangeListener peerMapChangeListener)
	{
		synchronized (peerMapChangeListeners)
		{
			peerMapChangeListeners.add(peerMapChangeListener);
		}
	}

	@Override
	public void removePeerMapChangeListener(PeerMapChangeListener peerMapChangeListener)
	{
		synchronized (peerMapChangeListeners)
		{
			peerMapChangeListeners.add(peerMapChangeListener);
		}
	}

	@Override
	public void addPeerOfflineListener(PeerOfflineListener peerListener)
	{
		synchronized (peerListeners)
		{
			peerListeners.add(peerListener);
		}
	}

	@Override
	public void removePeerOfflineListener(PeerOfflineListener peerListener)
	{
		synchronized (peerListeners)
		{
			peerListeners.remove(peerListener);
		}
	}

	/**
	 * Notifies on insert. Since listeners are never changed, this is thread
	 * safe.
	 * 
	 * @param peerAddress The address of the inserted peers
	 */
	private void notifyInsert(PeerAddress peerAddress)
	{
		synchronized (peerMapChangeListeners)
		{
			for (PeerMapChangeListener listener : peerMapChangeListeners)
				listener.peerInserted(peerAddress);
		}
	}

	/**
	 * Notifies on remove. Since listeners are never changed, this is thread
	 * safe.
	 * 
	 * @param peerAddress The address of the removed peers
	 */
	private void notifyRemove(PeerAddress peerAddress)
	{
		synchronized (peerMapChangeListeners)
		{
			for (PeerMapChangeListener listener : peerMapChangeListeners)
				listener.peerRemoved(peerAddress);
		}
	}

	/**
	 * Notifies on update. This method is thread safe.
	 * 
	 * @param peerAddress
	 */
	private void notifyUpdate(PeerAddress peerAddress)
	{
		synchronized (peerMapChangeListeners)
		{
			for (PeerMapChangeListener listener : peerMapChangeListeners)
				listener.peerUpdated(peerAddress);
		}
	}

	private void notifyOffline(PeerAddress peerAddress)
	{
		synchronized (peerListeners)
		{
			for (PeerOfflineListener listener : peerListeners)
				listener.peerOffline(peerAddress);
		}
	}

	private void notifyPeerFail(PeerAddress peerAddress)
	{
		synchronized (peerListeners)
		{
			for (PeerOfflineListener listener : peerListeners)
				listener.peerFail(peerAddress);
		}
	}

	/**
	 * The peerCount keeps track of the total number of peer in the system.
	 * 
	 * @return the total number of peers
	 */
	@Override
	public int size()
	{
		return peerCount.get();
	}

	@Override
	public Number160 self()
	{
		return self;
	}

	@Override
	public boolean peerOnline(final PeerAddress remotePeer, final PeerAddress referrer)
	{
		boolean firstHand = referrer == null;
		// always trust first hand information
		if (firstHand)
		{
			synchronized (peerOfflineLogs)
			{
				peerOfflineLogs.remove(remotePeer);
			}
		}
		// don't add nodes with zero node id, do not add myself and do not add
		// nodes marked as bad
		if (remotePeer.getID().isZero() || self().equals(remotePeer.getID())
				|| isPeerRemovedTemporarly(remotePeer)
				|| filteredAddresses.contains(remotePeer.getInetAddress())
				|| remotePeer.isFirewalledTCP())
			return false;
		final int classMember = classMember(remotePeer.getID());
		final Map<Number160, PeerAddress> map = peerMap.get(classMember);
		if (size() < maxPeers || map.containsKey(remotePeer.getID()))
		{
			// this updates stats and schedules peer for maintenance
			prepareInsertOrUpdate(remotePeer, firstHand);
			// fill it in, regardless of the bag size, also update if we
			// already have this peer, we update the last seen time with
			// this
			return insertOrUpdate(map, remotePeer, classMember);
		}
		else
		{
			// check if we should add a node and remove another node
			if (map.size() < bagSize)
			{
				// the class is not full, remove other nodes!
				if (removeLatestEntryExceedingBagSize())
				{
					// this updates stats and schedules peer for maintenance
					prepareInsertOrUpdate(remotePeer, firstHand);
					return insertOrUpdate(map, remotePeer, classMember);
				}
			}
		}
		return false;
	}

	@Override
	public boolean peerOffline(final PeerAddress remotePeer, boolean force)
	{
		if (logger.isDebugEnabled())
			logger.info("peer " + remotePeer + " is offline");
		if (remotePeer.getID().isZero() || self().equals(remotePeer.getID()))
			return false;
		notifyPeerFail(remotePeer);
		Log log;
		synchronized (peerOfflineLogs)
		{
			log = peerOfflineLogs.get(remotePeer);
			if (log == null)
			{
				log = new Log();
				peerOfflineLogs.put(remotePeer, log);
			}
		}
		synchronized (log)
		{
			if (!force)
			{
				if (shouldPeerBeRemoved(log))
				{
					remove(remotePeer);
					return true;
				}
				log.inc();
				if (!shouldPeerBeRemoved(log))
				{
					peerMapStat.removeStat(remotePeer);
					addToMaintenanceQueue(remotePeer);
					return false;
				}
			}
			else
				log.set(maxFail);
		}
		remove(remotePeer);
		return true;
	}

	private boolean remove(PeerAddress remotePeer)
	{
		//System.err.println("remove");
		final int classMember = classMember(remotePeer.getID());
		final Map<Number160, PeerAddress> map = peerMap.get(classMember);
		final boolean retVal = map.remove(remotePeer.getID()) != null;
		if (retVal)
		{
			if (map.size() <= bagSize)
				overSizeBagIndex.remove(classMember);
			removeFromMaintenance(remotePeer);
			peerCount.decrementAndGet();
			notifyRemove(remotePeer);
		}
		notifyOffline(remotePeer);
		return retVal;
	}

	private void prepareInsertOrUpdate(PeerAddress remotePeer, boolean firstHand)
	{
		if (firstHand)
		{
			peerMapStat.setSeenOnlineTime(remotePeer);
			// get the amount of milliseconds for the online time
			long online = peerMapStat.online(remotePeer);
			// get the time we want to wait between maintenance checks
			if (maintenanceTimeoutsSeconds.length > 0)
			{
				long time = maintenanceTimeoutsSeconds[peerMapStat.getChecked(remotePeer)] * 1000L;
				// if we have a higer online time than the maintenance time,
				// increase checked to increase the maintenace interval.
				if (online >= time)
					peerMapStat.incChecked(remotePeer);
			}
		}
		addToMaintenanceQueue(remotePeer);
	}

	private void addToMaintenanceQueue(PeerAddress remotePeer)
	{
		if (maintenanceTimeoutsSeconds.length == 0)
			return;
		long scheduledCheck;
		if (peerMapStat.getLastSeenOnlineTime(remotePeer) == 0)
			// we need to check now!
			scheduledCheck = System.currentTimeMillis();
		else
		{
			// check for next schedule
			int checked = peerMapStat.getChecked(remotePeer);
			if (checked >= maintenanceTimeoutsSeconds.length)
				checked = maintenanceTimeoutsSeconds.length - 1;
			scheduledCheck = System.currentTimeMillis()
					+ (maintenanceTimeoutsSeconds[checked] * 1000L);
		}
		synchronized (maintenance)
		{
			maintenance.put(remotePeer, scheduledCheck);
		}
	}

	@Override
	public Collection<PeerAddress> peersForMaintenance()
	{
		Collection<PeerAddress> result = new ArrayList<PeerAddress>();
		synchronized (maintenance)
		{
			for (Iterator<Map.Entry<PeerAddress, Long>> iterator = maintenance.entrySet()
					.iterator(); iterator.hasNext();)
			{
				Map.Entry<PeerAddress, Long> entry = iterator.next();
				if (entry.getValue() < System.currentTimeMillis())
				{
					iterator.remove();
					result.add(entry.getKey());
				}
			}
		}
		return result;
	}

	private void removeFromMaintenance(PeerAddress peerAddress)
	{
		synchronized (maintenance)
		{
			maintenance.remove(peerAddress);
		}
	}

	/**
	 * Adds a peer to the set. If a peer reaches the bag size, the class is
	 * reported to the oversizebag. Furthermore, it notifies listeners about an
	 * insert.
	 * 
	 * @param set The set to add the peer
	 * @param remotePeer The remote peer to add
	 * @param classMember The class memeber, which is used to report oversize.
	 * @return True if the peer could be added. If the peer is already in, it
	 *         returns false
	 */
	private boolean insertOrUpdate(final Map<Number160, PeerAddress> map,
			final PeerAddress remotePeer, final int classMember)
	{
		boolean retVal;
		synchronized (map)
		{
			retVal = !map.containsKey(remotePeer.getID());
			map.put(remotePeer.getID(), remotePeer);
			if (retVal && map.size() > bagSize)
				overSizeBagIndex.put(classMember, this);
		}
		if (retVal)
		{
			peerCount.incrementAndGet();
			notifyInsert(remotePeer);
		}
		else
		{
			notifyUpdate(remotePeer);
		}
		return retVal;
	}

	/**
	 * This method returns peers that are over sized. The peers that have been
	 * seen latest stay.
	 * 
	 * @return True if we could remove an oversized peer
	 */
	private boolean removeLatestEntryExceedingBagSize()
	{
		// I think this is good enough for checking.
		// final int CHECK_AT_MOST = bagSize * 2;
		for (int classMember : overSizeBagIndex.keySet())
		{
			final Map<Number160, PeerAddress> map = peerMap.get(classMember);
			if (map.size() > bagSize)
			{
				long maxValue = Long.MAX_VALUE;
				int counter = 0;
				PeerAddress removePeerAddress = null;
				synchronized (map)
				{
					for (PeerAddress peerAddress : map.values())
					{
						final long lastSeenOline = peerMapStat.getLastSeenOnlineTime(peerAddress);
						if (lastSeenOline < maxValue)
						{
							maxValue = lastSeenOline;
							removePeerAddress = peerAddress;
						}
						// TODO: idea use a score system rather than
						// lastSeenOnline, as we might have old reliable peers.
						if (maxValue == 0)
							break;
						counter++;
					}
				}
				if (removePeerAddress != null)
				{
					final boolean retVal = map.remove(removePeerAddress.getID()) != null;
					if (retVal)
					{
						if (map.size() <= bagSize)
							overSizeBagIndex.remove(classMember);
						removeFromMaintenance(removePeerAddress);
						peerCount.decrementAndGet();
						notifyRemove(removePeerAddress);
					}
					return retVal;
				}
			}
		}
		return false;
	}

	private boolean shouldPeerBeRemoved(Log log)
	{
		return System.currentTimeMillis() - log.getLastOffline() <= cacheTimeout
				&& log.getCounter() >= maxFail;
	}

	@Override
	public boolean isPeerRemovedTemporarly(PeerAddress remoteNode)
	{
		Log log;
		synchronized (peerOfflineLogs)
		{
			log = peerOfflineLogs.get(remoteNode);
		}
		if (log != null)
		{
			synchronized (log)
			{
				if (shouldPeerBeRemoved(log))
					return true;
				else if (System.currentTimeMillis() - log.getLastOffline() > cacheTimeout)
				{
					// remove the peer if timeout occured
					synchronized (peerOfflineLogs)
					{
						peerOfflineLogs.remove(remoteNode);
					}
				}
			}
		}
		return false;
	}

	@Override
	public boolean contains(PeerAddress peerAddress)
	{
		final int classMember = classMember(peerAddress.getID());
		Map<Number160, PeerAddress> tmp = peerMap.get(classMember);
		return tmp.containsKey(peerAddress.getID());
	}

	@Override
	public SortedSet<PeerAddress> closePeers(final Number160 id, final int atLeast)
	{
		final SortedSet<PeerAddress> set = new TreeSet<PeerAddress>(createPeerComparator(id));
		// special treatment, as we can start iterating from 0
		if (self().equals(id))
		{
			for (int j = 0; set.size() < atLeast && j < Number160.BITS; j++)
			{
				Map<Number160, PeerAddress> tmp = peerMap.get(j);
				synchronized (tmp)
				{
					set.addAll(tmp.values());
				}
			}
			return set;
		}
		final int classMember = classMember(id);
		Map<Number160, PeerAddress> tmp = peerMap.get(classMember);
		synchronized (tmp)
		{
			set.addAll(tmp.values());
		}
		if (set.size() >= atLeast)
			return set;
		// first go down, all the way...
		for (int i = classMember - 1; i >= 0; i--)
		{
			tmp = peerMap.get(i);
			synchronized (tmp)
			{
				set.addAll(tmp.values());
			}
		}
		if (set.size() >= atLeast)
			return set;
		// go up... these ones will be larger than our distance
		for (int i = classMember + 1; set.size() < atLeast && i < Number160.BITS; i++)
		{
			tmp = peerMap.get(i);
			synchronized (tmp)
			{
				set.addAll(tmp.values());
			}
		}
		return set;
	}

	@Override
	public int isCloser(Number160 id, PeerAddress rn, PeerAddress rn2)
	{
		return isKadCloser(id, rn, rn2);
	}

	@Override
	public int isCloser(Number160 id, Number160 rn, Number160 rn2)
	{
		return distance(id, rn).compareTo(distance(id, rn2));
	}

	/**
	 * 
	 * 
	 * @see PeerMap.routing.Routing#isCloser(java.math.BigInteger,
	 *      PeerAddress.routing.NodeAddress, PeerAddress.routing.NodeAddress)
	 * @param key The key to search for
	 * @param rn2 The remote node on the routing path to node close to key
	 * @param rn An other remote node on the routing path to node close to key
	 * @return True if rn2 is closer or has the same distance to key as rn
	 */
	/**
	 * Returns -1 if the first remote node is closer to the key, if the
	 * secondBITS is closer, then 1 is returned. If both are equal, 0 is
	 * returned
	 * 
	 * @param id The id as a distance reference
	 * @param rn The peer to test if closer to the id
	 * @param rn2 The other peer to test if closer to the id
	 * @return -1 if first peer is closer, 1 otherwise, 0 if both are equal
	 */
	private static int isKadCloser(Number160 id, PeerAddress rn, PeerAddress rn2)
	{
		return distance(id, rn.getID()).compareTo(distance(id, rn2.getID()));
	}

	/**
	 * Returns the number of the class that this id belongs to
	 * 
	 * @param remoteID The id to test
	 * @return The number of bits used in the difference.
	 */
	private int classMember(Number160 remoteID)
	{
		return classMember(self(), remoteID);
	}

	/**
	 * Returns the difference in terms of bit counts of two ids, minus 1. So two
	 * IDs with one bit difference are in the class 0.
	 * 
	 * @param id1 The first id
	 * @param id2 The second id
	 * @return returns the bit difference and -1 if they are equal
	 */
	static int classMember(Number160 id1, Number160 id2)
	{
		return distance(id1, id2).bitLength() - 1;
	}

	/**
	 * The distance metric is the XOR metric.
	 * 
	 * @param id1 The first id
	 * @param id2 The second id
	 * @return The distance
	 */
	static Number160 distance(Number160 id1, Number160 id2)
	{
		return id1.xor(id2);
	}

	/*
	 * kadRouting.add(pa1, true); public double expectedNumberOfNodes() {
	 * BigInteger mean = getMeanDistance(); if (mean != BigInteger.ZERO) {
	 * BigInteger totalNodes = BigInteger.valueOf(2); totalNodes =
	 * totalNodes.pow(Number160.BITS); return
	 * totalNodes.divide(mean).doubleValue(); } else return 0; }
	 * 
	 * private BigInteger getMeanDistance() { // there is a high chance to not
	 * have dropped nodes that are close to // me. If the bag is full, then we
	 * know, that the first BAG_SIZE 2 // entries have no dropped nodes.
	 * However, if the bag is not full, then // this assumption does not hold
	 * anymore. But I think that a worst case // scenario,
	 * SortedSet<PeerAddress> list = getAtMostCloseNodes(BAG_SIZE 2); BigInteger
	 * oldDistance = BigInteger.ZERO; // System.err.print("["); List<BigInteger>
	 * distances = new ArrayList<BigInteger>(); for (PeerAddress nodeAddress :
	 * list) { Number160 distance = distance(self(), nodeAddress.getID());
	 * BigInteger diff = distance.subtract(oldDistance); distances.add(diff);
	 * oldDistance = distance; // SkadRouting.add(pa1,
	 * true);ystem.err.print(diff); // System.err.print(","); }
	 * Collections.sort(distances); // System.err.println("]"); final int
	 * removeNr = (int) ((BAG_SIZE 2) .15); if (distances.size() > removeNr 2) {
	 * for (int i = 0; i < removeNr; i++) { distances.remove(0);
	 * distances.remove(distances.size() - 1); } BigInteger cumulatedDistance =
	 * BigInteger.ZERO; for (BigInteger distance : distances) cumulatedDistance
	 * = cumulatedDistance.add(distance); // System.err.println("FF " +
	 * cumulatedDistance + "nr " + // distances.size()); cumulatedDistance =
	 * cumulatedDistance.divide(BigInteger.valueOf(distances.size())); //
	 * System.err.println("TT " + cumulatedDistance + "nr " + //
	 * distances.size()); return cumulatedDistance; } else return
	 * BigInteger.ZERO; }
	 * 
	 * private SortedSet<PeerAddress> getAtMostCloseNodes(int nr) {
	 * SortedSet<PeerAddress> set = createCloseNodesSet2(self()); for (int i =
	 * 0; i < Number160.BITS; i++) { Set<PeerAddress> list2 = peerMap.get(i);
	 * for (PeerAddress nodeAddress : list2) { set.add(nodeAddress); if
	 * (list.size() >= nr) return set; } } return set; }
	 */
	@Override
	public String toString()
	{
		final StringBuilder sb = new StringBuilder("I'm node ");
		sb.append(self()).append("\n");
		for (int i = 0; i < Number160.BITS; i++)
		{
			final Map<Number160, PeerAddress> tmp = peerMap.get(i);
			if (tmp.size() > 0)
			{
				sb.append("class:").append(i).append("->\n");
				synchronized (tmp)
				{
					for (PeerAddress node : tmp.values())
						sb.append("node:").append(node).append(",");
				}
			}
		}
		return sb.toString();
	}

	@Override
	public Comparator<PeerAddress> createPeerComparator(final Number160 id)
	{
		return new Comparator<PeerAddress>()
		{
			public int compare(PeerAddress remoteNode, PeerAddress remoteNode2)
			{
				return isKadCloser(id, remoteNode, remoteNode2);
			}
		};
	}

	@Override
	public Comparator<PeerAddress> createPeerComparator()
	{
		return createPeerComparator(self);
	}

	@Override
	public Collection<PeerAddress> getAll()
	{
		Collection<PeerAddress> all = new ArrayList<PeerAddress>();
		for (Map<Number160, PeerAddress> map : peerMap)
		{
			synchronized (peerMap)
			{
				all.addAll(map.values());
			}
		}
		return all;
	}

	@Override
	public void addAddressFilter(InetAddress address)
	{
		filteredAddresses.add(address);
	}
}