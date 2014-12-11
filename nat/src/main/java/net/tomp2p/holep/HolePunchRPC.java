package net.tomp2p.holep;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.tomp2p.connection.ConnectionConfiguration;
import net.tomp2p.connection.DefaultConnectionConfiguration;
import net.tomp2p.connection.Dispatcher;
import net.tomp2p.connection.HolePunchInitiator;
import net.tomp2p.connection.PeerConnection;
import net.tomp2p.connection.Responder;
import net.tomp2p.futures.BaseFutureAdapter;
import net.tomp2p.futures.FutureChannelCreator;
import net.tomp2p.futures.FutureDone;
import net.tomp2p.message.Message;
import net.tomp2p.message.Message.Type;
import net.tomp2p.message.NeighborSet;
import net.tomp2p.p2p.Peer;
import net.tomp2p.peers.PeerAddress;
import net.tomp2p.relay.BaseRelayForwarderRPC;
import net.tomp2p.rpc.DispatchHandler;
import net.tomp2p.rpc.RPC;
import net.tomp2p.rpc.RPC.Commands;
import net.tomp2p.utils.Pair;

public class HolePunchRPC extends DispatchHandler {

	private static final Logger LOG = LoggerFactory.getLogger(HolePunchRPC.class);

	private final Peer peer;
	private final ConnectionConfiguration config;

	public HolePunchRPC(Peer peer) {
		super(peer.peerBean(), peer.connectionBean());
		register(RPC.Commands.HOLEP.getNr());
		this.peer = peer;
		this.config = new DefaultConnectionConfiguration();
	}

	@Override
	public void handleResponse(Message message, PeerConnection peerConnection, boolean sign, Responder responder) throws Exception {
		// This means, that a new Holepunch has been initiated.
		if (message.type() == Message.Type.REQUEST_1) {
			LOG.debug("New HolePunch process initiated from peer " + message.sender().peerId() + " to peer " + message.recipient().peerId()
					+ " on ports: " + message.intList().toString());
			forwardPorts(message, peerConnection, responder);
		}
		// This means that peer1 has answered
		else if (message.type() == Message.Type.REQUEST_2) {
			LOG.debug("HolePunch initiated on peer: " + message.recipient().peerId());
			handleHolePunch(message, peerConnection, responder);
		} else if (message.type() == Message.Type.REQUEST_3) {
			LOG.debug("HolePunch initiated on peer: " + message.recipient().peerId());
			handleHolePunchReply(message, peerConnection, responder);
		} else {
			throw new IllegalArgumentException("Message Content is wrong!");
		}
	}

	private void handleHolePunchReply(Message message, PeerConnection peerConnection, Responder responder) {
		responder.response(createResponseMessage(message, Type.OK));
	}
	
	private void handleHolePunch(final Message message, final PeerConnection peerConnection, final Responder responder) {
		HolePuncher holePuncher = new HolePuncher(peer, message.intList().size(), HolePunchInitiator.IDLE_UDP_SECONDS, message);
		FutureDone<Message> replyMessage = holePuncher.replyHolePunch();
		replyMessage.addListener(new BaseFutureAdapter<FutureDone<Message>>() {

			@Override
			public void operationComplete(FutureDone<Message> future) throws Exception {
				if (future.isSuccess()) {
					responder.response(future.object());
				} else {
					handleFail(message, responder, "Fail while replying to hole punch attempt");
				}
				
			}
		});
	}


	/**
	 * This method first forwards a initHolePunch request to start the hole
	 * punching procedure on the target peer. Then it waits for the response
	 * from the target peer and forwards this response back to the initiating
	 * peer.
	 * 
	 * @param message
	 * @param peerConnection
	 * @param responder
	 */
	private void forwardPorts(final Message message, PeerConnection peerConnection, final Responder responder) {
		final BaseRelayForwarderRPC forwarder = extractRelayForwarder(message);
		if (forwarder != null) {
			final Message forwardMessage = createForwardPortsMessage(message, forwarder.unreachablePeerAddress());

			FutureDone<Message> response = forwarder.forwardToUnreachable(forwardMessage);
			response.addListener(new BaseFutureAdapter<FutureDone<Message>>() {
				@Override
				public void operationComplete(FutureDone<Message> future) throws Exception {
					if (future.isSuccess()) {
						Message answerMessage = createResponseMessage(message, Message.Type.OK);
						for (Integer i : future.object().intList()) {
							answerMessage.intValue(i);
						}
						answerMessage.command(Commands.HOLEP.getNr());

						LOG.debug("Returing from relay to requester: {}", answerMessage);
						responder.response(answerMessage);
					} else {
						responder.failed(Type.DENIED, "Relaying message failed: " + future.failedReason());
					}
				}
			});
		} else {
			handleFail(message, responder, "No RelayForwarder registered for peerId=" + message.recipient().peerId().toString());
		}
	}

	private Message createForwardPortsMessage(Message message, PeerAddress recipient) {
		Message forwardMessage = createMessage(recipient, RPC.Commands.HOLEP.getNr(), Message.Type.REQUEST_2);
		forwardMessage.version(message.version());
		forwardMessage.messageId(message.messageId());

		// forward all ports to the unreachable peer2
		for (Integer port : message.intList()) {
			forwardMessage.intValue(port);
		}

		// transmit PeerAddress of unreachable Peer1
		final NeighborSet ns = new NeighborSet(1, new ArrayList<PeerAddress>(1));
		ns.add(message.sender());
		forwardMessage.neighborsSet(ns);

		return forwardMessage;
	}

	/**
	 * This method extracts a registered {@link BaseRelayForwarderRPC} from the
	 * {@link Dispatcher}. This RelayForwarder can then be used to extract the
	 * {@link PeerConnection} to the unreachable Peer we want to contact.
	 * 
	 * @param unreachablePeerId
	 *            the unreachable peer
	 * @return forwarder
	 */
	private BaseRelayForwarderRPC extractRelayForwarder(final Message message) {
		final Dispatcher dispatcher = peer.connectionBean().dispatcher();
		final Map<Integer, DispatchHandler> ioHandlers = dispatcher.searchHandlerMap(peer.peerID(), message.recipient().peerId());
		for (DispatchHandler handler : ioHandlers.values()) {
			if (handler instanceof BaseRelayForwarderRPC) {
				return (BaseRelayForwarderRPC) handler;
			}
		}
		return null;
	}

	/**
	 * This method is called if something went wrong while the hole punching
	 * procedure. It responds then with a {@link Type}.EXCEPTION message.
	 * 
	 * @param message
	 * @param responder
	 * @param failReason
	 */
	private void handleFail(final Message message, final Responder responder, final String failReason) {
		LOG.error(failReason);
		responder.response(createResponseMessage(message, Type.EXCEPTION));
	}

}