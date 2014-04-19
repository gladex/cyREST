package org.cytoscape.rest.internal.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;

import org.cytoscape.application.CyApplicationManager;
import org.cytoscape.io.read.CyNetworkReader;
import org.cytoscape.io.read.InputStreamTaskFactory;
import org.cytoscape.io.write.CyNetworkViewWriterFactory;
import org.cytoscape.io.write.CyWriter;
import org.cytoscape.model.CyNetwork;
import org.cytoscape.model.CyNetworkFactory;
import org.cytoscape.model.CyNetworkManager;
import org.cytoscape.model.subnetwork.CyRootNetwork;
import org.cytoscape.model.subnetwork.CySubNetwork;
import org.cytoscape.rest.DataMapper;
import org.cytoscape.rest.TaskFactoryManager;
import org.cytoscape.rest.internal.datamapper.CyNetworkView2CytoscapejsMapper;
import org.cytoscape.rest.internal.serializer.CyTableSerializer;
import org.cytoscape.rest.internal.task.RestTaskManager;
import org.cytoscape.rest.internal.translator.CyNetwork2JSONTranslator;
import org.cytoscape.task.NetworkCollectionTaskFactory;
import org.cytoscape.task.NetworkTaskFactory;
import org.cytoscape.view.model.CyNetworkView;
import org.cytoscape.view.model.CyNetworkViewManager;
import org.cytoscape.view.presentation.property.BasicVisualLexicon;
import org.cytoscape.view.vizmap.VisualMappingManager;
import org.cytoscape.view.vizmap.VisualStyle;
import org.cytoscape.work.TaskFactory;
import org.cytoscape.work.TaskIterator;
import org.cytoscape.work.TaskManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;

@Singleton
@Path("/v1")
// API version
public class NetworkDataService {

	private final static Logger logger = LoggerFactory.getLogger(NetworkDataService.class);

	// Preset types
	private static final String JSON = "json";
	private static final String DEF_COLLECTION_PREFIX = "Posted: ";
	private static final String NETWORKS = "networks";

	// /////////////// Inject Dependencies ///////////////////////

	@Context
	private CyApplicationManager applicationManager;

	@Context
	private CyNetworkManager networkManager;

	@Context
	private CyNetworkViewManager networkViewManager;

	@Context
	private CyNetworkFactory networkFactory;

	@Context
	private TaskFactoryManager tfManager;

	@Context
	private InputStreamTaskFactory cytoscapeJsReaderFactory;

	@Context
	private VisualMappingManager vmm;

	@Context
	private CyNetworkViewWriterFactory cytoscapeJsWriterFactory;

	private final CyNetwork2JSONTranslator network2jsonTranslator;
	private final DataMapper<CyNetworkView> cytoscapejsView;

	public NetworkDataService() {
		this.cytoscapejsView = new CyNetworkView2CytoscapejsMapper();

		this.network2jsonTranslator = new CyNetwork2JSONTranslator();
	}

	@GET
	@Path("/" + NETWORKS + "/count")
	@Produces(MediaType.APPLICATION_JSON)
	public String getNetworkCount() {
		// Extract number of networks in current session
		final int count = networkManager.getNetworkSet().size();
		final JsonFactory factory = new JsonFactory();

		String result = null;
		ByteArrayOutputStream stream = new ByteArrayOutputStream();
		JsonGenerator generator = null;
		try {
			generator = factory.createGenerator(stream);
			generator.writeStartObject();
			generator.writeFieldName("networkCount");
			generator.writeNumber(count);
			generator.writeEndObject();
			generator.close();
			result = stream.toString();
			stream.close();
		} catch (IOException e) {
			e.printStackTrace();
			logger.error("Could not create stream.", e);
			throw new WebApplicationException(500);
		}

		return result;
	}

	@GET
	@Path("/" + NETWORKS)
	@Produces(MediaType.APPLICATION_JSON)
	public String getNetworks() {
		final Set<CyNetwork> networks = networkManager.getNetworkSet();
		StringBuilder result = new StringBuilder();
		result.append("[");

		for (final CyNetwork network : networks) {
			result.append(getNetworkString(network));
			result.append(",");
		}
		String jsonString = result.toString();
		jsonString = jsonString.substring(0, jsonString.length() - 1);

		return jsonString + "]";
	}

	/**
	 * GET method for CyNetwork.
	 * 
	 * @param id
	 *            - title or
	 * @param fileFormat
	 * @return
	 */
	@GET
	@Path("/" + NETWORKS + "/{id}/")
	@Produces(MediaType.APPLICATION_JSON)
	public String getNetwork(@PathParam("id") String id) {
		final CyNetwork network = findNetwork("suid", id);
		if (network == null) {
			throw new WebApplicationException(404);
		}

		return getNetworkString(network);
	}

	@GET
	@Path("/" + NETWORKS + "/{id}/tables/{tableType}")
	@Produces(MediaType.TEXT_PLAIN)
	public String getTable(@PathParam("id") String id, @PathParam("tableType") String tableType) {
		final CyNetwork network = findNetwork("suid", id);
		if (network == null) {
			throw new WebApplicationException(404);
		}

		if (tableType.equals("node")) {
			CyTableSerializer tableSerializer = new CyTableSerializer();
			try {
				final String result = tableSerializer.toCSV(network.getDefaultNodeTable());
				return result;
			} catch (Exception e) {
				e.printStackTrace();
			}
			throw new WebApplicationException(500);
		} else {
			throw new WebApplicationException(404);
		}
	}

	/**
	 * Create network from Cytoscape.js style JSON.
	 * 
	 * @param collection
	 *            Name of network collection.
	 * @param is
	 * @throws Exception
	 */
	@POST
	@Path("/" + NETWORKS)
	@Consumes(MediaType.APPLICATION_JSON)
	public void createNetwork(@DefaultValue(DEF_COLLECTION_PREFIX) @QueryParam("collection") String collection,
			final InputStream is, @Context HttpHeaders headers) throws Exception {

		final String userAgent = headers.getRequestHeader("user-agent").get(0);

		final TaskIterator it = cytoscapeJsReaderFactory.createTaskIterator(is, "test123");
		final CyNetworkReader reader = (CyNetworkReader) it.next();

		String collectionName = collection;
		if (collection.equals(DEF_COLLECTION_PREFIX)) {
			collectionName = collectionName + userAgent;
		}

		reader.run(null);

		CyNetwork[] networks = reader.getNetworks();
		CyNetwork newNetwork = networks[0];
		System.out.println("###LEN = " + networks.length);
		System.out.println("###read5 edges = " + newNetwork.getEdgeCount());
		System.out.println("###read5 nodes = " + newNetwork.getNodeCount());
		addNetwork(networks, reader, collectionName);
		is.close();
	}

	private final String getNetworkString(final CyNetwork network) {
		final ByteArrayOutputStream stream = new ByteArrayOutputStream();
		CyWriter writer = cytoscapeJsWriterFactory.createWriter(stream, network);
		String jsonString = null;
		try {
			writer.run(null);
			jsonString = stream.toString();
			stream.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return jsonString;
	}

	private final void addNetwork(final CyNetwork[] networks, final CyNetworkReader reader, final String collectionName) {
		final VisualStyle style = vmm.getCurrentVisualStyle();
		final List<CyNetworkView> results = new ArrayList<CyNetworkView>();

		for (final CyNetwork network : networks) {
			String networkName = network.getRow(network).get(CyNetwork.NAME, String.class);
			if (networkName == null || networkName.trim().length() == 0) {
				if (networkName == null)
					networkName = collectionName;

				network.getRow(network).set(CyNetwork.NAME, networkName);
			}
			networkManager.addNetwork(network);

			final int numGraphObjects = network.getNodeCount() + network.getEdgeCount();
			int viewThreshold = 10000;
			if (numGraphObjects < viewThreshold) {
				final CyNetworkView view = reader.buildCyNetworkView(network);
				networkViewManager.addNetworkView(view);
				vmm.setVisualStyle(style, view);
				style.apply(view);

				if (!view.isSet(BasicVisualLexicon.NETWORK_CENTER_X_LOCATION)
						&& !view.isSet(BasicVisualLexicon.NETWORK_CENTER_Y_LOCATION)
						&& !view.isSet(BasicVisualLexicon.NETWORK_CENTER_Z_LOCATION))
					view.fitContent();
				results.add(view);
			} else {
				// results.add(nullNetworkViewFactory.createNetworkView(network));
			}
		}

		// If this is a subnetwork, and there is only one subnetwork in the
		// root, check the name of the root network
		// If there is no name yet for the root network, set it the same as its
		// base subnetwork
		if (networks.length == 1) {
			System.out.println("####### root: " + collectionName);
			if (networks[0] instanceof CySubNetwork) {
				CySubNetwork subnet = (CySubNetwork) networks[0];
				final CyRootNetwork rootNet = subnet.getRootNetwork();
				String rootNetName = rootNet.getRow(rootNet).get(CyNetwork.NAME, String.class);
				rootNet.getRow(rootNet).set(CyNetwork.NAME, collectionName);
				// if (rootNetName == null || rootNetName.trim().length() == 0){
				// // The root network does not have a name yet, set it the same
				// as the base subnetwork
				// rootNet.getRow(rootNet).set(CyNetwork.NAME, collectionName);
				// }
			}
		}

		// // Make sure rootNetwork has a name
		// for (CyNetwork network : networks) {
		// if (network instanceof CySubNetwork){
		// CySubNetwork subNet = (CySubNetwork) network;
		// CyRootNetwork rootNet = subNet.getRootNetwork();
		//
		// String networkName = rootNet.getRow(rootNet).get(CyNetwork.NAME,
		// String.class);
		// if(networkName == null || networkName.trim().length() == 0) {
		// networkName = name;
		// if(networkName == null)
		// networkName = "? (Name is missing)";
		//
		// rootNet.getRow(rootNet).set(CyNetwork.NAME,
		// namingUtil.getSuggestedNetworkTitle(networkName));
		// }
		// }
		// }
	}

	@GET
	@Path("/" + NETWORKS + "/{id}.cyjson/")
	@Produces(MediaType.APPLICATION_JSON)
	public String getNetworkAsCyjson(@PathParam("id") String id,
			@DefaultValue("title") @QueryParam("idtype") String idType) {
		return network2jsonTranslator.translate(findNetwork(idType, id));
	}

	private CyNetwork findNetwork(final String idType, final String id) {
		final CyNetwork network;
		if (idType.equals("suid")) {
			try {
				final Long suid = Long.parseLong(id);
				network = networkManager.getNetwork(suid);
			} catch (NumberFormatException nfe) {
				throw new WebApplicationException(400);
			}

		} else if (idType.equals("title")) {
			network = getNetworkByTitle(id);
		} else {
			throw new WebApplicationException(400);
		}

		// Could not find network by the identifier.
		if (network == null)
			throw new WebApplicationException(404);

		return network;
	}

	@GET
	@Path("/" + NETWORKS + "/views/")
	@Produces(MediaType.APPLICATION_JSON)
	public String getCurrentNetworkView() {
		final CyNetworkView curView = applicationManager.getCurrentNetworkView();
		System.out.println("----Get current network VIEW");

		if (curView == null)
			throw new WebApplicationException(404);

		return cytoscapejsView.writeAsString(curView);
	}

	/**
	 * Create network by POST
	 * 
	 * @param json
	 * @param name
	 * @return
	 */
	@POST
	@Path("/networks/{title}/")
	@Consumes({ MediaType.APPLICATION_JSON })
	@Produces(MediaType.APPLICATION_JSON)
	public String postCustomer(String json, @PathParam("title") String name) {

		final CyNetwork network = networkFactory.createNetwork();
		network.getRow(network).set(CyNetwork.NAME, name);
		networkManager.addNetwork(network);
		String result = "Input is " + json;
		return result;
	}

	@GET
	@Path("/execute/{taskId}/{suid}")
	@Produces(MediaType.APPLICATION_JSON)
	public String runNetworkTask(@PathParam("suid") String suid, @PathParam("taskId") String taskName) {
		if (taskName != null) {
			NetworkTaskFactory tf = tfManager.getNetworkTaskFactory(taskName);
			System.out.println("TF from Map: " + tf);

			if (tf != null) {
				TaskIterator ti;

				System.out.println("## Got network tf: " + taskName);
				NetworkTaskFactory ntf = (NetworkTaskFactory) tf;
				final Long networkSUID = parseSUID(suid);
				final CyNetwork network = networkManager.getNetwork(networkSUID);
				ti = ntf.createTaskIterator(network);

				TaskManager tm = new RestTaskManager();
				tm.execute(ti);
				System.out.println("Finished Task: " + taskName);
			}
			return "{ \"currentNetwork\": " + applicationManager.getCurrentNetwork().getSUID() + "}";
		} else {
			return "Could not execute task.";
		}
	}

	@GET
	@Path("/execute/networks/{taskId}/{suid}")
	@Produces(MediaType.APPLICATION_JSON)
	public String runNetworkCollectionTask(@PathParam("suid") String suid, @PathParam("taskId") String taskName) {
		if (taskName != null) {
			NetworkCollectionTaskFactory tf = tfManager.getNetworkCollectionTaskFactory(taskName);
			System.out.println("Got TF: " + taskName);
			if (tf != null) {
				TaskIterator ti;

				System.out.println("Got network collection tf: " + taskName);
				NetworkCollectionTaskFactory ntf = (NetworkCollectionTaskFactory) tf;
				final Long networkSUID = parseSUID(suid);
				final CyNetwork network = networkManager.getNetwork(networkSUID);
				List<CyNetwork> networks = new ArrayList<CyNetwork>();
				networks.add(network);
				ti = ntf.createTaskIterator(networks);

				TaskManager tm = new RestTaskManager();
				tm.execute(ti);
				System.out.println("Finished Task: " + taskName);
			}
			return "OK";
		} else {
			return "Could not execute task.";
		}
	}

	@GET
	@Path("/execute/{taskId}")
	@Produces(MediaType.APPLICATION_JSON)
	public String runStatelessTask(@PathParam("taskId") String taskName) {
		if (taskName != null) {
			TaskFactory tf = tfManager.getTaskFactory(taskName);
			if (tf != null) {
				TaskIterator ti = tf.createTaskIterator();

				TaskManager tm = new RestTaskManager();
				tm.execute(ti);
				System.out.println("Finished Task: " + taskName);
			}
			return "OK";
		} else {
			return "Could not execute task.";
		}
	}

	private final Long parseSUID(final String stringID) {
		Long networkSUID = null;
		try {
			networkSUID = Long.parseLong(stringID);
		} catch (NumberFormatException ex) {
			logger.warn("Invalid SUID: " + stringID, ex);
			throw new IllegalArgumentException("Could not parse SUID: " + stringID);
		}

		return networkSUID;
	}

	private final CyNetwork getNetworkByTitle(final String title) {
		final Set<CyNetwork> networks = networkManager.getNetworkSet();

		for (final CyNetwork network : networks) {
			final String networkTitle = network.getRow(network).get(CyNetwork.NAME, String.class);
			if (networkTitle == null)
				continue;
			if (networkTitle.equals(title))
				return network;
		}

		// Not found
		return null;
	}
}
