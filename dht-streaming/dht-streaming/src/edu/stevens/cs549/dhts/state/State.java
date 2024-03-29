package edu.stevens.cs549.dhts.state;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

import javax.ws.rs.client.Client;
import javax.ws.rs.core.MediaType;

import org.glassfish.jersey.media.sse.EventOutput;
import org.glassfish.jersey.media.sse.EventSource;
import org.glassfish.jersey.media.sse.OutboundEvent;
import org.glassfish.jersey.media.sse.SseBroadcaster;
import org.glassfish.jersey.server.BroadcasterListener;

import edu.stevens.cs549.dhts.activity.DHTBase;
import edu.stevens.cs549.dhts.activity.IDHTNode;
import edu.stevens.cs549.dhts.activity.NodeInfo;
import edu.stevens.cs549.dhts.resource.TableRep;
import edu.stevens.cs549.dhts.resource.TableRow;

/**
 * 
 * @author dduggan
 */
public class State implements IState, IRouting {

	static final long serialVersionUID = 0L;

	public static Logger log = Logger.getLogger(State.class.getCanonicalName());
	
	protected NodeInfo info;

	public State(NodeInfo info) {
		super();
		this.info = info;
		this.predecessor = null;
		this.successor = info;

		this.finger = new NodeInfo[NKEYS];
		for (int i = 0; i < NKEYS; i++) {
			finger[i] = info;
		}

	}

	/*
	 * Get the info for this DHT node.
	 */
	public NodeInfo getNodeInfo() {
		return info;
	}

	/*
	 * Local table operations.
	 */
	private Persist.Table dict = Persist.newTable();

	@SuppressWarnings("unused")
	private Persist.Table backup = Persist.newTable();

	@SuppressWarnings("unused")
	private NodeInfo backupSucc = null;

	public synchronized String[] get(String k) {
		List<String> vl = dict.get(k);
		if (vl == null) {
			return null;
		} else {
			String[] va = new String[vl.size()];
			return vl.toArray(va);
		}
	}

	public synchronized void add(String k, String v) {
		List<String> vl = dict.get(k);
		if (vl == null) {
			vl = new ArrayList<String>();
			dict.put(k, vl);
		}
		vl.add(v);
		// TODO done: broadcast an event to any listeners
		broadcastAddition(k, v);
	}

	public synchronized void delete(String k, String v) {
		List<String> vs = dict.get(k);
		if (vs != null)
			vs.remove(v);
	}

	public synchronized void clear() {
		dict.clear();
	}

	/*
	 * Operations for transferring state between predecessor and successor.
	 */

	/*
	 * Successor: Extract the bindings from the successor node.
	 */
	public synchronized TableRep extractBindings(int predId) {
		return Persist.extractBindings(predId, info, successor, dict);
	}

	public synchronized TableRep extractBindings() {
		return Persist.extractBindings(info, successor, dict);
	}

	/*
	 * Successor: Drop the bindings that are transferred to the predecessor.
	 */
	public synchronized void dropBindings(int predId) {
		Persist.dropBindings(dict, predId, getNodeInfo().id);
	}

	/*
	 * Predecessor: Install the transferred bindings.
	 */
	public synchronized void installBindings(TableRep db) {
		dict = Persist.installBindings(dict, db);
	}

	/*
	 * Predecessor: Back up bindings from the successor.
	 */
	public synchronized void backupBindings(TableRep db) {
		backup = Persist.backupBindings(db);
		// backupSucc = db.getSucc();
	}

	public synchronized void backupSucc(TableRep db) {
		backupSucc = db.getSucc();
	}

	/*
	 * A never-used operation for storing state in a file.
	 */
	public synchronized void backup(String filename) throws IOException {
		Persist.save(info, successor, dict, filename);
	}

	public synchronized void reload(String filename) throws IOException {
		dict = Persist.load(filename);
	}

	public synchronized void display() {
		PrintWriter wr = new PrintWriter(System.out);
		Persist.display(dict, wr);
	}

	/*
	 * Routing operations.
	 */

	private NodeInfo predecessor = null;
	private NodeInfo successor = null;

	private NodeInfo[] finger;

	public synchronized void setPred(NodeInfo pred) {
		predecessor = pred;
	}

	public NodeInfo getPred() {
		return predecessor;
	}

	public synchronized void setSucc(NodeInfo succ) {
		successor = succ;
	}

	public NodeInfo getSucc() {
		return successor;
	}

	public synchronized void setFinger(int i, NodeInfo info) {
		/*
		 * TODO done: Set the ith finger.
		 */
		finger[i] = info;
	}

	public synchronized NodeInfo getFinger(int i) {
		/*
		 * TODO done: Get the ith finger.
		 */
		return finger[i];
	}

	public synchronized NodeInfo closestPrecedingFinger(int id) {
		/*
		 * TODO done: Get closest preceding finger for id, to continue search at that
		 * node. Hint: See DHTBase.inInterval()
		 */
		for (int i = NFINGERS -1; i >= 0; i--) {
			if (inInterval(finger[i].id, info.id, id)) {
				return finger[i];
			}
		}
		return info;
	}
	
	private boolean inInterval(int id, int LB, int UB) {
		if (LB < UB) {
			UB = UB - LB;
			id = id - LB;
			return id >= 0 && id < UB;
		} else if (UB < LB) {
			UB = (UB + (NKEYS - LB)) % NKEYS;
			id = (id + (NKEYS - LB)) % NKEYS;
			return id >= 0 && id < UB;
		} else {
			return false;
		}
	}

	public synchronized void routes() {
		PrintWriter wr = new PrintWriter(System.out);
		wr.println("Predecessor: " + predecessor);
		wr.println("Successor  : " + successor);
		wr.println("Fingers:");
		wr.printf("%7s  %3s  %s\n", "Formula", "Key", "Succ");
		wr.printf("%7s  %3s  %s\n", "-------", "---", "----");
		for (int i = 0, exp = 1; i < IRouting.NFINGERS; i++, exp = 2 * exp) {
			wr.printf(" %2d+2^%1d  %3d  [id=%2d,uri=%s]%n", info.id, i, (info.id + exp) % IRouting.NKEYS, finger[i].id,
					finger[i].addr);
		}
		wr.flush();
	}
	
	
	/*
	 * Used to prevent a race condition in the join protocol.
	 */
	
	public static enum JoinState {
		NOT_JOINED,
		JOINING,
		JOINED
	}
	
	private JoinState joinState = JoinState.NOT_JOINED;
	
	private Lock joinStateLock = new ReentrantLock();
	
	private Condition joined = joinStateLock.newCondition();
	
	public void startJoin() {
		joinStateLock.lock();
		try {
			joinState = JoinState.JOINING;
		} finally {
			joinStateLock.unlock();
		}
	}
	
	public void joinCheck() {
		// Called by any operations that should block during join protocol.
		// Currently that is getPred() (for the case where we are joining a 
		// single-node network).
		joinStateLock.lock();
		try {
			while (joinState == JoinState.JOINING) {
				joined.await();
			}
		} catch (InterruptedException e) {
			log.info("Join check loop was interrupted.");
		} finally {
			joinStateLock.unlock();
		}
	}
	
	public void finishJoin() {
		joinStateLock.lock();
		try {
			joinState = JoinState.JOINED;
			joined.signalAll();
		} finally {
			joinStateLock.unlock();
		}
	}
	
	/*
	 * Server-side listeners for new bindings.
	 */
	
	private Map<String,SseBroadcaster> listeners = new HashMap<String,SseBroadcaster>();
	
	private Map<Integer,Map<String,EventOutput>> outputs = new HashMap<Integer,Map<String,EventOutput>>();
	
	@Override
	public void addListener(int id, String key, EventOutput os) {
		// TODO done add listener into listeners and outputs
		if (listeners.containsKey(key)) {
			listeners.get(key).add(os);
		} else {
			SseBroadcaster broadcaster = new SseBroadcaster();
			broadcaster.add(os);
			listeners.put(key, broadcaster);
		}
		if (outputs.containsKey(id)) {
			outputs.get(id).put(key, os);
		} else {
			Map<String, EventOutput> map = new HashMap<>();
			map.put(key, os);
			outputs.put(id, map);
		}
	}
	
	public void removeListener(int id, String key) {
		// TODO done Close the event output stream.
		if (outputs.containsKey(id)) {
			EventOutput output = outputs.get(id).remove(key);
			
			if (listeners.containsKey(key)) {
				listeners.get(key).remove(output);
			}
			
			try {
				output.close();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}
	
	private void broadcastAddition(String key, String value) {
		// TODO done broadcast an added binding (use IDHTNode.NEW_BINDING_EVENT for event name).
		if (listeners.containsKey(key)) {
			OutboundEvent.Builder builder = new OutboundEvent.Builder();
			OutboundEvent event = builder.name(IDHTNode.NEW_BINDING_EVENT).data(String.class, value).build();
			listeners.get(key).broadcast(event);
		}
	}
	
	private void broadcastChange(NodeInfo pred, String key) {
		if (listeners.containsKey(key)) {
			OutboundEvent.Builder builder = new OutboundEvent.Builder();
			OutboundEvent event = builder.name(IDHTNode.BINDING_CHANGE_EVENT).data(NodeInfo.class, pred).build();
			listeners.get(key).broadcast(event);
		}
	}
	
	// Check if node is to be transferred
	public void checkListener(NodeInfo pred, TableRep db) {
		for (TableRow row : db.entry) {
			// Notify client to reconnect
			if (listeners.containsKey(row.key)) {
				broadcastChange(pred, row.key);
			}
		}
	}
	
	/*
	 * Client-side callbacks for new binding notifications.
	 */
	
	private Map<String,EventSource> callbacks = new HashMap<String,EventSource>();
	
	public void addCallback(String key, EventSource is) {
		removeCallback(key);
		callbacks.put(key, is);
	}
	
	public void removeCallback(String key) {
		// TODO done remove an existing callback (if any) for bindings on key.
		// Be sure to close the event stream from the broadcaster.
		if (callbacks.containsKey(key)) {
			callbacks.remove(key).close();
		}
	}
	
	public void listCallbacks() {
		PrintWriter wr = new PrintWriter(System.out);
		if (callbacks.isEmpty()) {
			wr.println("No listeners defined.");
		} else {
			wr.println("Listeners defined for:");
			for (Entry<String, EventSource> entry : callbacks.entrySet()) {
				if (entry.getValue().isOpen()) {
					wr.println("  " + entry.getKey());
				} else {
					wr.println("  " + entry.getKey() + " (closed)");
					callbacks.remove(entry.getKey());
				}
			}
		}
		wr.flush();
	}

	
}
