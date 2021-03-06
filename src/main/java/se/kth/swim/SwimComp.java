/*
 * Copyright (C) 2009 Swedish Institute of Computer Science (SICS) Copyright (C)
 * 2009 Royal Institute of Technology (KTH)
 *
 * GVoD is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package se.kth.swim;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.kth.swim.msg.PingPongType;
import se.kth.swim.msg.Status;
import se.kth.swim.msg.StatusType;
import se.kth.swim.msg.net.*;
import se.kth.swim.nat.NatedAddress;
import se.sics.kompics.ComponentDefinition;
import se.sics.kompics.Handler;
import se.sics.kompics.Init;
import se.sics.kompics.Positive;
import se.sics.kompics.Start;
import se.sics.kompics.Stop;
import se.sics.kompics.network.Network;
import se.sics.kompics.timer.*;
import se.sics.kompics.timer.Timer;

/**
 * @author Alex Ormenisan <aaor@sics.se>
 */
public class SwimComp extends ComponentDefinition {

    private static final Logger log = LoggerFactory.getLogger(SwimComp.class);
    private Positive<Network> network = requires(Network.class);
    private Positive<Timer> timer = requires(Timer.class);
    private final NatedAddress selfAddress;
    private final List<NatedAddress> bootstrapNodes;
    private Map<Integer,Status> localStateNodes = new TreeMap<>();
    private Map<Integer,UUID> suspectedNodes = new TreeMap<>();
    private Map<UUID,NatedAddress> nodeswithPingReq = new TreeMap<>();
    private Map<Integer, Status> updateLocalview = new TreeMap<>();
    private UUID pingTimeoutId;
    private UUID pongTimeoutId;
    private Status peerStatus;
    private int receivedPings = 0;
    private int incarnationNumber;

    public SwimComp(SwimInit init) {
        this.selfAddress = init.selfAddress;
        log.info("{} initiating...", selfAddress);
        this.bootstrapNodes = init.bootstrapNodes;
        subscribe(handleStart, control);
        subscribe(handleStop, control);
        subscribe(handlePing, network);
        subscribe(pongHandler, network);
        subscribe(netPingRequestHandler, network);
        subscribe(pingRequestTimeoutHandler,timer);
        subscribe(suspectTimeoutHandler,timer);
        subscribe(deathTimeoutHandler, timer);
        subscribe(handlePingTimeout, timer);
        subscribe(pongTimeoutHandler, timer);
        subscribe(printHandler, timer);

    }
    public static class SwimInit extends Init<SwimComp> {
        public final NatedAddress selfAddress;
        public final List<NatedAddress> bootstrapNodes;

        public SwimInit(NatedAddress selfAddress, List<NatedAddress> bootstrapNodes) {
            this.selfAddress = selfAddress;
            this.bootstrapNodes = bootstrapNodes;
        }
    }

    private Handler<Start> handleStart = new Handler<Start>() {
        @Override
        public void handle(Start event) {
            log.info("{} At SwimComp Start Handler starting...", new Object[]{selfAddress.getId()});
            incarnationNumber = 0;
            localStateNodes.put(selfAddress.getId(),new Status(StatusType.ALIVE,incarnationNumber,  selfAddress,selfAddress));
            if (!bootstrapNodes.isEmpty()) {
                schedulePeriodicPing();
                shedulePeriodicLocalViewPrint();
            }
        }

    };
    private Handler<Stop> handleStop = new Handler<Stop>() {

        @Override
        public void handle(Stop event) {
            log.info("{} stopping...", new Object[]{selfAddress.getId()});
            if (pingTimeoutId != null) {
                cancelPeriodicPing();
            }
        }

    };
    //-------------------------------------- Event Handlers ---------------------------------------------------------//
    //                                                                                                               //
    //---------------------------------------------------------------------------------------------------------------//

    private Handler<NetPing> handlePing = new Handler<NetPing>() {
        @Override
        public void handle(NetPing netPingEvent) {
            NatedAddress sourcePeer = netPingEvent.getHeader().getSource();
            receivedPings++;
            localStateNodes.put(selfAddress.getId(), new Status(StatusType.ALIVE,incarnationNumber,selfAddress,selfAddress));
            updateLocalState(netPingEvent.getContent().getViewUpdate());
            if(netPingEvent.getContent().getPingPongType().equals(PingPongType.PINGREQUEST)){
                trigger(new NetPong(selfAddress,sourcePeer, PingPongType.PINGREQUEST,netPingEvent.getContent().getPongTimeoutId(),localStateNodes),network);
            }else{
                trigger(new NetPong(selfAddress,sourcePeer, PingPongType.PINGPONG,netPingEvent.getContent().getPongTimeoutId(),localStateNodes),network);
            }
        }
    };
    private Handler<NetPong> pongHandler = new Handler<NetPong>() {
        @Override
        public void handle(NetPong netPongEvent) {
            if( netPongEvent.getContent().getPingPongType().equals(PingPongType.PINGPONG)){
                if( netPongEvent.getContent().getPongTimeoutId() != null ) {
                    cancelPongTimeout(netPongEvent.getContent().getPongTimeoutId(), netPongEvent.getSource());
                    updateLocalview.clear();
                    updateLocalview.putAll(netPongEvent.getContent().getViewUpdate());
                    Status status = updateLocalview.get(netPongEvent.getSource().getId());
                    updateLocalState(updateLocalview);
                }
            }else {
                cancelPingRequestTimeout(netPongEvent.getContent().getPongTimeoutId(),netPongEvent.getSource());
                NatedAddress target = nodeswithPingReq.remove(netPongEvent.getContent().getPongTimeoutId());
                trigger(new NetPong(netPongEvent.getSource(), target,PingPongType.PINGPONG,netPongEvent.getContent().getPongTimeoutId(),localStateNodes),network);
                updateLocalview.clear();
                updateLocalview.putAll(netPongEvent.getContent().getViewUpdate());
                Status status = updateLocalview.get(netPongEvent.getSource().getId());
                updateLocalState(updateLocalview);
            }
        }
    };

    private Handler<PingTimeout> handlePingTimeout = new Handler<PingTimeout>() {
        @Override
        public void handle(se.kth.swim.SwimComp.PingTimeout event) {
            List<NatedAddress> peers = selectRandomPeer(selfAddress,bootstrapNodes,1);
            for(NatedAddress peer: peers){
                pongTimeoutId = schedulePongTimeout(peer,2000);
                trigger(new NetPing(selfAddress, peer, PingPongType.PINGPONG,pongTimeoutId,localStateNodes), network);
            }
        }
    };


    private Handler<PongTimeout> pongTimeoutHandler = new Handler<PongTimeout>() {
        @Override
        public void handle(PongTimeout pongTimeoutEvent) {
            UUID pingSuspectRequesttId;
            List<NatedAddress> lst = getListofLocalState(localStateNodes);
            List<NatedAddress> peerstoProbe = selectRandomPeer(selfAddress,lst ,2);
            pingSuspectRequesttId = pongTimeoutEvent.getPongTimeoutId();
            if(peerstoProbe != null){
                for(NatedAddress peer : peerstoProbe){
                    trigger(new NetPingRequest(selfAddress,peer,pongTimeoutEvent.getSuspectedPeer(),pingSuspectRequesttId),network);
                }
                scheduleSuspectTimeout(pongTimeoutEvent.getSuspectedPeer(),pingSuspectRequesttId,2000);
            }
        }
    };

    private Handler<NetPingRequest> netPingRequestHandler = new Handler<NetPingRequest>() {
        @Override
        public void handle(NetPingRequest netPingRequestEvent) {
            nodeswithPingReq.put(netPingRequestEvent.getContent().getPingSuspectRequesttId(),netPingRequestEvent.getSource());
            trigger(new NetPing(selfAddress,netPingRequestEvent.getContent().getPeerToPing(), PingPongType.PINGREQUEST,
                    netPingRequestEvent.getContent().getPingSuspectRequesttId(), localStateNodes),network);
            schedulePingRequestTimeout(netPingRequestEvent.getContent().getPingSuspectRequesttId(),2000);
        }
    };

    private Handler<PingRequestTimeout> pingRequestTimeoutHandler = new Handler<PingRequestTimeout>() {
        @Override
        public void handle(PingRequestTimeout pingRequestTimeout) {
            nodeswithPingReq.remove(pingRequestTimeout.getPingSuspectRequesttId());
        }
    };

    private Handler<SuspectTimeout> suspectTimeoutHandler = new Handler<SuspectTimeout>() {
        @Override
        public void handle(SuspectTimeout suspectTimeoutEvent) {
            updateLocalview.clear();
            Status localStatus = localStateNodes.get(suspectTimeoutEvent.getDeadPeer().getId());
            if(localStatus != null){
                updateLocalview.put(suspectTimeoutEvent.getDeadPeer().getId(),
                        new Status(StatusType.SUSPECTED,localStatus.getIncarnationNo(), suspectTimeoutEvent.getDeadPeer(),selfAddress));
            }
            updateLocalState(updateLocalview);
        }
    };
    private Handler<DeathTimeout> deathTimeoutHandler = new Handler<DeathTimeout>() {
        @Override
        public void handle(DeathTimeout deathTimeoutEvent) {
            updateLocalview.clear();
            Status localStatus = localStateNodes.get(deathTimeoutEvent.getDeadPeer().getId());
            if(localStatus != null){
                updateLocalview.put(deathTimeoutEvent.getDeadPeer().getId(),
                        new Status(StatusType.DEAD,localStatus.getIncarnationNo(),  deathTimeoutEvent.getDeadPeer(),selfAddress));
            }
            updateLocalState(updateLocalview);
        }
    };
    private Handler<PeriodicLocalViewPrint> printHandler = new Handler<PeriodicLocalViewPrint>() {
        @Override
        public void handle(PeriodicLocalViewPrint deathTimeoutEvent) {
            printLocalView();
        }
    };


    private void updateLocalState(Map<Integer,Status> peers) {
        peers.forEach((key_natAddress, value_status) ->{
            localStateNodes.merge(key_natAddress, value_status, (local, incoming) ->
                    mergeViews(key_natAddress,incoming,local) );

        });

    }
    private Status mergeViews(Integer key_natAddress, Status incoming, Status local) {
        Status newStatusValue;
        if((key_natAddress.equals(selfAddress.getId()) && (incoming.isSuspected())) ){
            incarnationNumber++;
            newStatusValue = new Status(StatusType.ALIVE,incarnationNumber, selfAddress,selfAddress);
            //Local Alive
        }else if( (incoming.isSuspected() && local.isAlive())){
            if(local.getIncarnationNo()  > incoming.getIncarnationNo() ){
                newStatusValue = new Status(local.getStatusType(),local.getIncarnationNo(),  local.getstatusOwner(),selfAddress);
            }else{
                newStatusValue = new Status(incoming.getStatusType(),incoming.getIncarnationNo(),  incoming.getstatusOwner(),selfAddress);
                if(incoming.getStatusType().equals(StatusType.SUSPECTED)){
                    UUID tID = scheduleDeathTimeout(local.getstatusOwner(),10000);
                    suspectedNodes.put(local.getstatusOwner().getId(),tID);
                }
            }

        }else if((incoming.isAlive() && local.isAlive()) ){
            if(local.getIncarnationNo()  > incoming.getIncarnationNo() ){
                newStatusValue = new Status(local.getStatusType(),local.getIncarnationNo(),  local.getstatusOwner(),selfAddress);
            }else{
                newStatusValue = new Status(incoming.getStatusType(),incoming.getIncarnationNo(), incoming.getstatusOwner(),selfAddress);
            }
        }
        else if( (incoming.isAlive() && local.isSuspected()) ){
            if(local.getIncarnationNo()  >= incoming.getIncarnationNo() ){
                newStatusValue = new Status(local.getStatusType(),local.getIncarnationNo(), local.getstatusOwner(),selfAddress);
            }else{
                newStatusValue = new Status(incoming.getStatusType(),incoming.getIncarnationNo(), incoming.getstatusOwner(),selfAddress);
                if(incoming.getStatusType().equals(StatusType.ALIVE)){
                    UUID tID = suspectedNodes.remove(incoming.getstatusOwner().getId());
                    if(tID != null)
                        cancelDeathTimeout(tID);
                }
            }

        }else if( (incoming.isSuspected() && local.isSuspected())){

            if(local.getIncarnationNo()  > incoming.getIncarnationNo() ){
                newStatusValue = new Status(local.getStatusType(),local.getIncarnationNo(), local.getstatusOwner(),selfAddress);
            }else{
                newStatusValue = new Status(incoming.getStatusType(),incoming.getIncarnationNo(), incoming.getstatusOwner(),selfAddress);
            }

        }else if(incoming.isDead()){
            newStatusValue = new Status(incoming.getStatusType(),incoming.getIncarnationNo(),  incoming.getstatusOwner(),selfAddress);

        }else if((incoming.isAlive())&&(local.isDead())){
            newStatusValue = new Status(local.getStatusType(),local.getIncarnationNo(), local.getstatusOwner(),selfAddress);
        }
        else{
            newStatusValue = new Status(incoming.getStatusType(),incoming.getIncarnationNo(),  incoming.getstatusOwner(),selfAddress);
        }
        //log.info("Peer :{} with View :{} learned that Peer :{} is :{}",new Object[]{ key_natAddress,localStateNodes.keySet(),newStatusValue.getstatusOwner(),newStatusValue.getStatusType()} );
        peerStatus = newStatusValue;
        return newStatusValue;
    }

    //-------------------------------------- Random Peer Sampling----------------------------------------------------//
    //                                                                                                               //
    //---------------------------------------------------------------------------------------------------------------//
    protected List<NatedAddress> selectRandomPeer1(List<NatedAddress> peerlist,int nrofRequiredNodes)
    {
        Random random =new Random();
        NatedAddress randompeer;
        List<NatedAddress> randompeers = new ArrayList<>();
        while(randompeers.size() < nrofRequiredNodes){
            randompeer = peerlist.get(random.nextInt(peerlist.size()));
            if(!(randompeer.equals(selfAddress) && randompeer != null))
                randompeers.add(randompeer);
        }
        return randompeers;
    }


    private List<NatedAddress> getListofLocalState(Map<Integer, Status> state){
        List<NatedAddress> listofPeers = new ArrayList<>();
        Map<Integer,NatedAddress> natedAddressMap = new TreeMap<>();
        for(NatedAddress natedAddress : bootstrapNodes){
            natedAddressMap.put(natedAddress.getId(),natedAddress);
        }
        for(Integer peer : state.keySet()){
            if(natedAddressMap.get(peer) != null)
                listofPeers.add(natedAddressMap.get(peer));
        }
        return listofPeers;
    }


    private static List<NatedAddress> shuffleandSelect(NatedAddress selfAddress, List<NatedAddress> peerlist, int nrofRequiredNodes, Random r) {
        int peerlistLen= peerlist.size();
        Random random =new Random();
        NatedAddress randompeer;
        List<NatedAddress> randompeers = new ArrayList<>();
        if (peerlistLen < nrofRequiredNodes) return null;
        for (int i = peerlistLen - 1; i >= peerlistLen - nrofRequiredNodes; --i)
        { Collections.swap(peerlist, i , r.nextInt(i + 1)); }
        while(randompeers.size() < nrofRequiredNodes){
            randompeer = peerlist.get(random.nextInt(peerlistLen));
            if(!(randompeer.equals(selfAddress) && randompeer != null))
                randompeers.add(randompeer);
            //break;
        }
        return randompeers;
    }
    private static List<NatedAddress> selectRandomPeer(NatedAddress selfAddress,List<NatedAddress> listofpeers, int nrofRequiredNodes) {
        List<NatedAddress> randompeers = shuffleandSelect(selfAddress, listofpeers, nrofRequiredNodes, ThreadLocalRandom.current());
        return randompeers;
    }
    public void printLocalView() {
        StringBuilder suspected = new StringBuilder();
        StringBuilder dead = new StringBuilder();
        int Alive=0,Suspected=0,Dead=0;
        for(Status status:localStateNodes.values()) {
            if(status.getStatusType().equals(StatusType.ALIVE)){
                Alive++;
            }else if((status.getStatusType().equals(StatusType.SUSPECTED))){
                dead.append("  ");
                suspected.append( status.getstatusOwner());
                suspected.append("  ");
                Suspected++;
            }else if(status.getStatusType().equals(StatusType.DEAD)){
                dead.append("  ");
                dead.append( status.getstatusOwner());
                dead.append("  ");
                Dead++;
            }
        }
        String deadD = dead.toString();
        String suspectedD = suspected.toString();
        log.info("Peer {} has {} Alive, {} Suspected, {} Dead, total {} ", new Object[]{selfAddress,Alive,Suspected,Dead, Alive+Suspected+Dead});
        log.info("Local View Suspected nodes :{}  Dead Nodes:{}", new Object[]{ suspectedD,deadD});

    }
    //-------------------------------------- Timeout Schedulers------------------------------------------------------//
    //                                                                                                               //
    //---------------------------------------------------------------------------------------------------------------//
    private void schedulePeriodicPing() {
        SchedulePeriodicTimeout spt = new SchedulePeriodicTimeout(3000, 3000);
        PingTimeout sc = new PingTimeout(spt);
        spt.setTimeoutEvent(sc);
        trigger(spt, timer);
    }
    private void shedulePeriodicLocalViewPrint(){
        SchedulePeriodicTimeout spt = new SchedulePeriodicTimeout(3000, 3000);
        PeriodicLocalViewPrint sc = new PeriodicLocalViewPrint(spt);
        spt.setTimeoutEvent(sc);
        trigger(spt, timer);
    }
    private UUID schedulePongTimeout(NatedAddress randomPeer,long delay) {
        ScheduleTimeout scheduleTimeout = new ScheduleTimeout(delay);//2 seconds
        PongTimeout sc = new PongTimeout(scheduleTimeout,randomPeer);
        scheduleTimeout.setTimeoutEvent(sc);
        UUID pongTimeoutId = sc.getTimeoutId();
        trigger(scheduleTimeout, timer);
        return pongTimeoutId;
    }
    private void scheduleSuspectTimeout(NatedAddress suspectedPeer, UUID pingRequesttId,long delay) {
        ScheduleTimeout scheduleTimeout = new ScheduleTimeout(delay);
        SuspectTimeout sc = new SuspectTimeout(scheduleTimeout,suspectedPeer,pingRequesttId);
        scheduleTimeout.setTimeoutEvent(sc);
        trigger(scheduleTimeout, timer);
    }
    private void schedulePingRequestTimeout(UUID pingSuspectRequesttId,long delay) {
        ScheduleTimeout scheduleTimeout = new ScheduleTimeout(delay);
        PingRequestTimeout sc = new PingRequestTimeout(scheduleTimeout,pingSuspectRequesttId);
        scheduleTimeout.setTimeoutEvent(sc);
        trigger(scheduleTimeout, timer);
    }
    private UUID scheduleDeathTimeout(NatedAddress deadPeer, long delay) {
        ScheduleTimeout scheduleTimeout = new ScheduleTimeout(delay);
        DeathTimeout sc = new DeathTimeout(scheduleTimeout,deadPeer);
        scheduleTimeout.setTimeoutEvent(sc);
        UUID suspectTID = sc.getTimeoutId();
        trigger(scheduleTimeout, timer);
        return suspectTID;
    }
    private void cancelPingRequestTimeout(UUID pongTimeoutId, NatedAddress source) {
        CancelTimeout cpt = new CancelTimeout(pongTimeoutId);
        trigger(cpt, timer);
    }
    private void cancelPeriodicPing() {
        CancelTimeout cpt = new CancelTimeout(pingTimeoutId);
        trigger(cpt, timer);
        pingTimeoutId = null;
    }
    private void cancelPongTimeout(UUID timeoutId, NatedAddress source) {
        trigger(new CancelTimeout(timeoutId), timer);
        pongTimeoutId = null;
    }
    private void cancelDeathTimeout(UUID timeoutId) {
        trigger(new CancelTimeout(timeoutId), timer);
    }


    //-------------------------------------- Timeout Events ---------------------------------------------------------//
    //                                                                                                               //
    //---------------------------------------------------------------------------------------------------------------//

    private static class PingTimeout extends Timeout {
        UUID tID;
        public PingTimeout(SchedulePeriodicTimeout request) {
            super(request);
        }
    }

    private class PongTimeout extends Timeout{
        private NatedAddress peer;
        private  UUID pongTIDd;
        public PongTimeout(ScheduleTimeout schedulePeriodicTimeout, NatedAddress peer) {
            super(schedulePeriodicTimeout);
            this.peer = peer;
            pongTimeoutId = getTimeoutId();
            this.pongTIDd = pongTimeoutId;

        }
        public NatedAddress getSuspectedPeer(){
            return peer;
        }

        public UUID getPongTimeoutId() {
            return pongTIDd;
        }
    }
    private class SuspectTimeout extends Timeout{
        private NatedAddress deadPeer;
        private UUID suspectTimeoutID;
        public SuspectTimeout(ScheduleTimeout schedulePeriodicTimeout, NatedAddress peer, UUID pingRequesttId) {
            super(schedulePeriodicTimeout);
            deadPeer = peer;
            suspectTimeoutID = pingRequesttId;
        }
        public UUID getSuspectTimeoutID() {
            return suspectTimeoutID;
        }

        public NatedAddress getDeadPeer(){
            return deadPeer;
        }
    }

    private class PingRequestTimeout extends Timeout{
        private UUID pingSuspectRequesttId;
        public PingRequestTimeout(ScheduleTimeout scheduleTimeout, UUID pingSuspectRequesttId) {
            super(scheduleTimeout);
            this.pingSuspectRequesttId = pingSuspectRequesttId;
        }

        public UUID getPingSuspectRequesttId() {
            return pingSuspectRequesttId;
        }
    }

    private class DeathTimeout  extends Timeout{
        private NatedAddress deadPeer;
        public DeathTimeout(ScheduleTimeout scheduleTimeout,NatedAddress peer) {
            super( scheduleTimeout);
            deadPeer = peer;
        }
        public NatedAddress getDeadPeer(){
            return deadPeer;
        }
    }

    private class PeriodicLocalViewPrint extends Timeout{
        public PeriodicLocalViewPrint(SchedulePeriodicTimeout spt) {
            super(spt);
        }
    }
}