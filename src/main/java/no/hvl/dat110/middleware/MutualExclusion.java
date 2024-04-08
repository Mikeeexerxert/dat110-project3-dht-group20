/**
 * 
 */
package no.hvl.dat110.middleware;

import java.math.BigInteger;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import no.hvl.dat110.rpc.interfaces.NodeInterface;
import no.hvl.dat110.util.LamportClock;
import no.hvl.dat110.util.Util;

/**
 * @author tdoy
 *
 */
public class MutualExclusion {
		
	private static final Logger logger = LogManager.getLogger(MutualExclusion.class);
	/** lock variables */
	private boolean CS_BUSY = false;						// indicate to be in critical section (accessing a shared resource) 
	private boolean WANTS_TO_ENTER_CS = false;				// indicate to want to enter CS
	private List<Message> queueack; 						// queue for acknowledged messages
	private List<Message> mutexqueue;						// queue for storing process that are denied permission. We really don't need this for quorum-protocol
	
	private LamportClock clock;								// lamport clock
	private Node node;
	
	public MutualExclusion(Node node) throws RemoteException {
		this.node = node;
		
		clock = new LamportClock();
		queueack = new ArrayList<Message>();
		mutexqueue = new ArrayList<Message>();
	}
	
	public synchronized void acquireLock() {
		CS_BUSY = true;
	}
	
	public void releaseLocks() {
		WANTS_TO_ENTER_CS = false;
		CS_BUSY = false;
	}

	public boolean doMutexRequest(Message message, byte[] updates) throws RemoteException {
		logger.info(node.nodename + " wants to access CS");
		// clear the queueack before requesting for votes
		queueack.clear();
		// clear the mutexqueue
		mutexqueue.clear();
		// increment clock
		clock.increment();
		// adjust the clock on the message, by calling the setClock on the message
		message.setClock(clock.getClock());
		// wants to access resource - set the appropriate lock variable
		WANTS_TO_ENTER_CS = true;
		// start MutualExclusion algorithm
		List<Message> activenodes = removeDuplicatePeersBeforeVoting();
		multicastMessage(message, activenodes);
		// first, call removeDuplicatePeersBeforeVoting. A peer can hold/contain 2 replicas of a file. This peer will appear twice
		if (areAllMessagesReturned(activenodes.size())) {
			acquireLock();
			node.broadcastUpdatetoPeers(updates);
			mutexqueue.clear();
			return true;
		}
		return false;
	}
	// multicast message to other processes including self
	private void multicastMessage(Message message, List<Message> activenodes) throws RemoteException {
		logger.info("Number of peers to vote = "+activenodes.size());
		// iterate over the activenodes
		for (Message peer : activenodes) {
			// obtain a stub for each node from the registry
			NodeInterface nodeStub = Util.getProcessStub(peer.getNodeName(), peer.getPort());
			// call onMutexRequestReceived()
			nodeStub.onMutexRequestReceived(message);
		}
	}
	
	public void onMutexRequestReceived(Message message) throws RemoteException {
		// increment the local clock
		clock.increment();
		// if message is from self, acknowledge, and call onMutexAcknowledgementReceived()
		if (message.getNodeName().equals(node.nodename)) {
			message.setAcknowledged(true);
			node.onMutexAcknowledgementReceived(message);
		} else {
			// acknowledge the message and add it to the queueack
			message.setAcknowledged(true);
			queueack.add(message);
			// check the logical clock of the sending process
			int caseid = -1;
			if (!WANTS_TO_ENTER_CS && !CS_BUSY) {
				caseid = 0;
			}
			else if (CS_BUSY) {
				caseid = 1;
			}
			else {
				caseid = 2;
			}
			doDecisionAlgorithm(message, mutexqueue, caseid);
		}
	}
	
	public void doDecisionAlgorithm(Message message, List<Message> queue, int condition) throws RemoteException {
		
		String procName = message.getNodeName();
		int port = message.getPort();
		
		switch(condition) {
		
			/** case 1: Receiver is not accessing shared resource and does not want to (send OK to sender) */
			case 0: {
				NodeInterface stub = Util.getProcessStub(procName, port);
				stub.onMutexAcknowledgementReceived(message);
				break;
			}
		
			/** case 2: Receiver already has access to the resource (dont reply but queue the request) */
			case 1: {
				queue.add(message);
				break;
			}
			/**
			 *  case 3: Receiver wants to access resource but is yet to (compare own message clock to received message's clock
			 *  the message with lower timestamp wins) - send OK if received is lower. Queue message if received is higher
			 */
			case 2: {
				int senderclock = message.getClock();
				int receiverclock = node.getMessage().getClock();
				if (receiverclock > senderclock) {
					queue.add(message);
				}
				else if (receiverclock == senderclock) {
					if (node.nodename.compareTo(procName) > 0) {
						queue.add(message);
					}
					else {
						NodeInterface stub = Util.getProcessStub(procName, port);
						stub.onMutexAcknowledgementReceived(message);
					}
				}
				else {
					NodeInterface stub = Util.getProcessStub(procName, port);
					stub.onMutexAcknowledgementReceived(message);
				}
				break;
			}
			default: break;
		}
		
	}
	
	public void onMutexAcknowledgementReceived(Message message) throws RemoteException {
		// add message to queueack
		queueack.add(message);
	}
	// multicast release locks message to other processes including self
	public void multicastReleaseLocks(Set<Message> activenodes) {
		logger.info("Releasing locks from = "+activenodes.size());
		for (Message peer : activenodes) {
			try {
				NodeInterface nodeStub = Util.getProcessStub(peer.getNodeName(), peer.getPort());
				nodeStub.releaseLocks();
			} catch (RemoteException e) {
				throw new RuntimeException(e);
			}
		}
	}
	
	private boolean areAllMessagesReturned(int numvoters) throws RemoteException {
		logger.info(node.getNodeName()+": size of queueack = "+queueack.size());
		if (queueack.size() == numvoters) {
			queueack.clear();
			return true;
		}
		return false;
	}
	
	private List<Message> removeDuplicatePeersBeforeVoting() {
		
		List<Message> uniquepeer = new ArrayList<Message>();
		for(Message p : node.activenodesforfile) {
			boolean found = false;
			for(Message p1 : uniquepeer) {
				if(p.getNodeName().equals(p1.getNodeName())) {
					found = true;
					break;
				}
			}
			if(!found)
				uniquepeer.add(p);
		}		
		return uniquepeer;
	}
}
