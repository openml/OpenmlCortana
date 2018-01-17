package org.openml.cortana;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.Options;
import org.apache.commons.io.FileUtils;
import org.json.JSONObject;
import org.openml.apiconnector.algorithms.Conversion;
import org.openml.apiconnector.io.OpenmlConnector;
import org.openml.apiconnector.settings.Config;
import org.openml.apiconnector.xml.EvaluationScore;
import org.openml.apiconnector.xml.Run;
import org.openml.apiconnector.xml.Run.Parameter_setting;
import org.openml.apiconnector.xml.UploadRun;
import org.openml.apiconnector.xstream.XstreamXmlMapping;
import org.openml.cortana.utils.Evaluations;
import org.openml.cortana.utils.SdFlow;
import org.openml.cortana.utils.XMLUtils;
import org.openml.cortana.xml.AutoRun;

public class CLI {

	public static final String[] TAGS = {"Cortana"};
	public static final Integer SD_TTID = 8;
	public static final String CORTANA_DEPENDENCY = "cortana.3103";
	
	/*public static void main(String[] args) throws Exception {
		OpenmlConnector openml = null;
		Integer task_id = null;
		String cortanaJar = null;
		boolean verbose = false;

		
		CommandLineParser parser = new GnuParser();
		Options options = new Options();
		options.addOption("config", true, "The config string describing the settings for API interaction");
		options.addOption("c", true, "The cortana jar location");
		options.addOption("t", true, "The task id");
		options.addOption("s", true, "The setup id (for setting search parameters)");
		options.addOption("v", false, "Verbose - Outputs cortana data");
	//	options.addOption("xml", true, "The auto run xml (for setting search parameters)");
		options.addOption("json", true, "The auto run json (for setting search parameters)");
		
		CommandLine cli  = parser.parse(options, args);
		Config config;
		if(cli.hasOption("-config") == false) {
			config = new Config();
		} else {
			config = new Config(cli.getOptionValue("config"));
		}
		
		if (config.getServer() != null) {
			openml = new OpenmlConnector(config.getServer(),config.getApiKey());
		} else {
			openml = new OpenmlConnector(config.getApiKey());
		}
		
		if (cli.hasOption("-c") == false) {
			throw new Exception("Cortana jar location parameter (-c) not set");
		} else {
			cortanaJar = cli.getOptionValue("c");
		}
		
		if (cli.hasOption("-v")) {
			verbose = true;
		}
		
		if (task_id == null) {
			if (cli.hasOption("-t") == false) {
				throw new Exception("Task parameter (-t) not set");
			} else {
				task_id = Integer.parseInt(cli.getOptionValue("t"));
			}
		}
		
		if (cli.hasOption("-s")) {
			String setupId = cli.getOptionValue("s");
			process(openml, task_id, cortanaJar, null, setupId, verbose, false);
			
		} else if (cli.hasOption("-json")) {
			String jsonString = cli.getOptionValue("json");
			process(openml, task_id, cortanaJar, null, jsonString, verbose, false);
			
		} else {
			throw new Exception("Search parameters not specified (-s or -json)");
		}
		
	}*/
	
	public static void process(OpenmlConnector openml, Integer taskId, String cortanaJarLocation, File saveDirectory, String setupIdOrJsonStr, boolean upload, boolean verbose) throws Exception {
		String current_run_name = "Cortana-Run-" + ManagementFactory.getRuntimeMXBean().getName();
		AutoRun ar;
		
		File autoRun;
		File subgroups;
		File resultTxt = null;
		File runFile;
		File datasetTmp = null;
		
		try {
			// first check if we obtained a setup id
			Integer setupId = Integer.parseInt(setupIdOrJsonStr);
			ar = XMLUtils.generateAutoRunFromSetup(openml, setupId, taskId, null);
		} catch(NumberFormatException nfe) {
			// probably a json sting 
			ar = XMLUtils.generateAutoRunFromJson(openml, setupIdOrJsonStr, taskId, saveDirectory);
		}
		
		if (saveDirectory == null) {
			autoRun = XMLUtils.autoRunToTmpFile(ar, current_run_name, null);
		} else {
			autoRun = XMLUtils.autoRunToFile(ar, new File(saveDirectory + "/" + current_run_name + ".xml"));
		}

		// find source data (for verification and cleaning up later)
		String datasetTmpPath = autoRun.getParentFile().getAbsolutePath() + "/" +  ar.getExperiment().getTable().getSource();
		datasetTmp = new File(datasetTmpPath);
		if (!datasetTmp.exists()) {
			throw new Exception("Could not find dataset to run SD: " + datasetTmpPath);
		}
		
		String cmd = "java -jar " + cortanaJarLocation + " " + autoRun.getAbsolutePath() + " 0 1";
	//	String[] cliArguments = {runXMLtmp.getAbsolutePath(), "0", "1"};
		
	//	System.out.println(xstream.toXML(ar));
		
		executeCommand(cmd,verbose);
		
		File dir = autoRun.getParentFile();
		
		for (File f : dir.listFiles()) {
			if (f.getName().startsWith(current_run_name) && f.getName().endsWith(".txt")) {
				
				if (resultTxt == null) {
					resultTxt = f;
				} else {
					throw new Exception("Multiple candidates for outputfile. ");
				}
			}
		}
		autoRun.delete();
		if (resultTxt == null) { 
			throw new Exception("Result txt file not found. "); 
		}
		if (saveDirectory == null) {
			subgroups = File.createTempFile("subgroups", ".csv");
		} else {
			subgroups = new File(saveDirectory + "/subgroups.csv");
		}
		resultTxt.renameTo(subgroups);
		datasetTmp.delete();
		
		// update search params with only relevant parameters
		Map<String, String> searchParams = ar.getExperiment().getSearchParameters().getParameters();
		String qualityMeasure = ar.getExperiment().getSearchParameters().getQuality_measure();
		int flow_id = SdFlow.getFlowId(openml);
		Parameter_setting[] params = new Parameter_setting[searchParams.size()];
		
		int i = 0;
		for (String key : searchParams.keySet()) {
			params[i++] = new Parameter_setting(flow_id, key, searchParams.get(key));
		}
		String setupString = new JSONObject(searchParams).toString();
		Run r = new Run(taskId, null, flow_id, setupString, params, TAGS);
		List<EvaluationScore> scores = Evaluations.extract(subgroups, qualityMeasure);
		for (EvaluationScore s : scores) { r.addOutputEvaluation(s); }
		String runFileStr = XstreamXmlMapping.getInstance().toXML(r);
		
		if (saveDirectory == null) { 
			runFile = Conversion.stringToTempFile(runFileStr, "run", "xml");
		} else {
			runFile = new File(saveDirectory + "/run.xml");
			FileUtils.writeStringToFile(runFile, runFileStr);
		}
			
		if (upload) {
			Map<String,File> uploadFiles = new HashMap<String, File>();
			uploadFiles.put("subgroups", subgroups);
			
			UploadRun ur = openml.runUpload(runFile, uploadFiles);
			
			Conversion.log("OK", "Upload", "Run uploaded. Rid: " + ur.getRun_id()); 
		}
	}
	
	private static boolean executeCommand(String cmd, boolean verbose) {
		Conversion.log("OK", "CMD", "Command: " + cmd);
		try {
			String line;

			//long startTime = bean.getCurrentThreadCpuTime();
			Process p = Runtime.getRuntime().exec(cmd);
			BufferedReader bri = new BufferedReader(new InputStreamReader(
					p.getInputStream()));
			BufferedReader bre = new BufferedReader(new InputStreamReader(
					p.getErrorStream()));
			while ((line = bri.readLine()) != null) {
				if (verbose) { Conversion.log("OK", "CMD", line); }
			}
			bri.close();
			//long cpuTimeMillies = (bean.getCurrentThreadCpuTime() - startTime) / 1000000;
			//Conversion.log("OK", "cputime", "" + cpuTimeMillies + "ms");
			
			while ((line = bre.readLine()) != null) {
				if (verbose) { Conversion.log("OK", "CMD", line); }
			}
			bre.close();
			p.waitFor();
			
			return true;
		} catch (Exception err) {
			err.printStackTrace();
			return false;
		}
	}
}
