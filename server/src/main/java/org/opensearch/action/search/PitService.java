/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.action.search;

import com.carrotsearch.hppc.cursors.ObjectCursor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.opensearch.action.ActionListener;
import org.opensearch.action.StepListener;
import org.opensearch.action.support.GroupedActionListener;
import org.opensearch.client.node.NodeClient;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.Strings;
import org.opensearch.common.inject.Inject;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.Transport;
import org.opensearch.transport.TransportException;
import org.opensearch.transport.TransportResponseHandler;
import org.opensearch.transport.TransportService;

import java.io.IOException;
import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Service class for PIT reusable functions
 */
public class PitService {

    private static final Logger logger = LogManager.getLogger(PitService.class);

    private final ClusterService clusterService;
    private final SearchTransportService searchTransportService;
    private final TransportService transportService;

    private final NodeClient nodeClient;

    @Inject
    public PitService(ClusterService clusterService, SearchTransportService searchTransportService, TransportService transportService, NodeClient nodeClient) {
        this.clusterService = clusterService;
        this.searchTransportService = searchTransportService;
        this.transportService = transportService;
        this.nodeClient = nodeClient;
    }

    /**
     * Delete list of pit contexts. Returns the details of success of operation per PIT ID.
     */
    public void deletePitContexts(
        Map<String, List<PitSearchContextIdForNode>> nodeToContextsMap,
        ActionListener<DeletePitResponse> listener
    ) {
        if (nodeToContextsMap.size() == 0) {
            listener.onResponse(new DeletePitResponse(Collections.emptyList()));
        }
        final Set<String> clusters = nodeToContextsMap.values()
            .stream()
            .flatMap(Collection::stream)
            .filter(ctx -> Strings.isEmpty(ctx.getSearchContextIdForNode().getClusterAlias()) == false)
            .map(c -> c.getSearchContextIdForNode().getClusterAlias())
            .collect(Collectors.toSet());
        StepListener<BiFunction<String, String, DiscoveryNode>> lookupListener = (StepListener<
            BiFunction<String, String, DiscoveryNode>>) SearchUtils.getConnectionLookupListener(
                searchTransportService.getRemoteClusterService(),
                clusterService.state(),
                clusters
            );
        logger.info(lookupListener.toString());
        StepListener<BiFunction<String, String, DiscoveryNode>> lookupListener1 = (StepListener<
            BiFunction<String, String, DiscoveryNode>>) SearchUtils.getConnectionLookupListener(
            searchTransportService.getRemoteClusterService(),
            clusterService.state(),
            new HashSet<>()
        );
        logger.info("The lookup lister 1");
        logger.info(lookupListener1.toString());
        lookupListener.whenComplete(nodeLookup -> {

            lookupListener1.whenComplete(nodeLookup1 -> {
                final GroupedActionListener<DeletePitResponse> groupedListener = getDeletePitGroupedListener(
                    listener,
                    nodeToContextsMap.size()
                );

                for (Map.Entry<String, List<PitSearchContextIdForNode>> entry : nodeToContextsMap.entrySet()) {
                    String clusterAlias = entry.getValue().get(0).getSearchContextIdForNode().getClusterAlias();

                    DiscoveryNode node = nodeLookup.apply(clusterAlias, entry.getValue().get(0).getSearchContextIdForNode().getNode());

                    if(node == null) {
                        logger.info("The node is nulll right now");
                        node = nodeLookup1.apply(clusterAlias, entry.getValue().get(0).getSearchContextIdForNode().getNode());
                    }

                    if (node == null) {

                        logger.error(
                            () -> new ParameterizedMessage("node [{}] not found", entry.getValue().get(0).getSearchContextIdForNode().getNode())
                        );
                        List<DeletePitInfo> deletePitInfos = new ArrayList<>();
                        logger.info("delete pit info");
                        logger.info(deletePitInfos.toString());
                        for (PitSearchContextIdForNode pitSearchContextIdForNode : entry.getValue()) {
                            deletePitInfos.add(new DeletePitInfo(false, pitSearchContextIdForNode.getPitId()));
                        }
                        groupedListener.onResponse(new DeletePitResponse(deletePitInfos));
                    } else {
                        logger.info(" node is not null now");
                        try {
                            final Transport.Connection connection = searchTransportService.getConnection(clusterAlias, node);
                            logger.info(" was able to make a connection" + connection.toString());
                            searchTransportService.sendFreePITContexts(connection, entry.getValue(), groupedListener);
                        } catch (Exception e) {
                            DiscoveryNode finalNode = node;
                            logger.info("Final Node"+ finalNode.toString());
                            logger.error(() -> new ParameterizedMessage("Delete PITs failed on node [{}]", finalNode.getName()), e);
                            List<DeletePitInfo> deletePitInfos = new ArrayList<>();
                            for (PitSearchContextIdForNode pitSearchContextIdForNode : entry.getValue()) {

                                deletePitInfos.add(new DeletePitInfo(false, pitSearchContextIdForNode.getPitId()));
                            }
                            groupedListener.onResponse(new DeletePitResponse(deletePitInfos));
                        }
                    }
                }
            }, listener::onFailure);

        }, listener::onFailure);
    }

    public GroupedActionListener<DeletePitResponse> getDeletePitGroupedListener(ActionListener<DeletePitResponse> listener, int size) {
        return new GroupedActionListener<>(new ActionListener<>() {
            @Override
            public void onResponse(final Collection<DeletePitResponse> responses) {
                Map<String, Boolean> pitIdToSucceededMap = new HashMap<>();
                for (DeletePitResponse response : responses) {
                    for (DeletePitInfo deletePitInfo : response.getDeletePitResults()) {
                        if (!pitIdToSucceededMap.containsKey(deletePitInfo.getPitId())) {
                            pitIdToSucceededMap.put(deletePitInfo.getPitId(), deletePitInfo.isSuccessful());
                        }
                        if (!deletePitInfo.isSuccessful()) {
                            logger.debug(() -> new ParameterizedMessage("Deleting PIT with ID {} failed ", deletePitInfo.getPitId()));
                            pitIdToSucceededMap.put(deletePitInfo.getPitId(), deletePitInfo.isSuccessful());
                        }
                    }
                }
                List<DeletePitInfo> deletePitResults = new ArrayList<>();
                for (Map.Entry<String, Boolean> entry : pitIdToSucceededMap.entrySet()) {
                    deletePitResults.add(new DeletePitInfo(entry.getValue(), entry.getKey()));
                }
                DeletePitResponse deletePitResponse = new DeletePitResponse(deletePitResults);
                listener.onResponse(deletePitResponse);
            }

            @Override
            public void onFailure(final Exception e) {
                logger.error("Delete PITs failed", e);
                listener.onFailure(e);
            }
        }, size);
    }

    /**
     * This method returns indices associated for each pit
     */
    public Map<String, String[]> getIndicesForPits(List<String> pitIds) {
        Map<String, String[]> pitToIndicesMap = new HashMap<>();
        for(String pitId : pitIds) {
            pitToIndicesMap.put(pitId, SearchContextId.decode(nodeClient.getNamedWriteableRegistry(), pitId).getActualIndices());
        }
        return pitToIndicesMap;
    }

    /**
     * Get all active point in time contexts
     */
    public void getAllPits(ActionListener<GetAllPitNodesResponse> getAllPitsListener) {
        final List<DiscoveryNode> nodes = new ArrayList<>();
        for (ObjectCursor<DiscoveryNode> cursor : clusterService.state().nodes().getDataNodes().values()) {
            DiscoveryNode node = cursor.value;
            nodes.add(node);
        }
        DiscoveryNode[] disNodesArr = nodes.toArray(new DiscoveryNode[nodes.size()]);
        transportService.sendRequest(
            transportService.getLocalNode(),
            NodesGetAllPitsAction.NAME,
            new GetAllPitNodesRequest(disNodesArr),
            new TransportResponseHandler<GetAllPitNodesResponse>() {

                @Override
                public void handleResponse(GetAllPitNodesResponse response) {
                    getAllPitsListener.onResponse(response);
                }

                @Override
                public void handleException(TransportException exp) {
                    getAllPitsListener.onFailure(exp);
                }

                @Override
                public String executor() {
                    return ThreadPool.Names.SAME;
                }

                @Override
                public GetAllPitNodesResponse read(StreamInput in) throws IOException {
                    return new GetAllPitNodesResponse(in);
                }
            }
        );
    }


}
