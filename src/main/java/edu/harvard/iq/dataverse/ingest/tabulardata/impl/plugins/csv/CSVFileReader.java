/*
 Copyright (C) 2005-2013, by the President and Fellows of Harvard College.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.

 Dataverse Network - A web application to share, preserve and analyze research data.
 Developed at the Institute for Quantitative Social Science, Harvard University.
 Version 3.0.
 */
package edu.harvard.iq.dataverse.ingest.tabulardata.impl.plugins.csv;

import java.io.*;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.text.*;
import java.util.logging.*;
import java.util.*;
import java.security.NoSuchAlgorithmException;

import javax.inject.Inject;

// Rosuda Wrappers and Methods for R-calls to Rserve
import org.rosuda.REngine.REXP;
import org.rosuda.REngine.REXPMismatchException;
import org.rosuda.REngine.RList;
import org.rosuda.REngine.Rserve.RFileInputStream;
import org.rosuda.REngine.Rserve.RFileOutputStream;
import org.rosuda.REngine.Rserve.*;

import edu.harvard.iq.dataverse.DataTable;
import edu.harvard.iq.dataverse.datavariable.DataVariable;
import edu.harvard.iq.dataverse.datavariable.VariableCategory;
import edu.harvard.iq.dataverse.datavariable.VariableFormatType;
import edu.harvard.iq.dataverse.datavariable.VariableServiceBean;

import edu.harvard.iq.dataverse.ingest.plugin.spi.*;
import edu.harvard.iq.dataverse.ingest.tabulardata.TabularDataFileReader;
import edu.harvard.iq.dataverse.ingest.tabulardata.spi.TabularDataFileReaderSpi;
import edu.harvard.iq.dataverse.ingest.tabulardata.TabularDataIngest;
import edu.harvard.iq.dataverse.rserve.*;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;

import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;

/**
 * Dataverse 4.0 implementation of <code>TabularDataFileReader</code> for the
 * plain CSV file with a variable name header.
 *
 *
 * @author Leonid Andreev
 *
 * This implementation uses external R-Scripts to do the bulk of the processing.
 */
public class CSVFileReader extends TabularDataFileReader {

    @Inject
    VariableServiceBean varService;

    private static final Logger dbglog = Logger.getLogger(CSVFileReader.class.getPackage().getName());
    private char delimiterChar = ',';

    public CSVFileReader(TabularDataFileReaderSpi originator) {
        super(originator);
    }

    private void init() throws IOException {
        Context ctx = null; 
        try {
            ctx = new InitialContext();
            varService = (VariableServiceBean) ctx.lookup("java:global/dataverse-4.0/VariableServiceBean");
        } catch (NamingException nex) {
            try {
                ctx = new InitialContext();
                varService = (VariableServiceBean) ctx.lookup("java:global/dataverse/VariableServiceBean");
            } catch (NamingException nex2) {
                dbglog.severe("Could not look up initial context, or the variable service in JNDI!");
                throw new IOException ("Could not look up initial context, or the variable service in JNDI!"); 
            }
        }
    }
    
    /**
     * Reads a CSV file, converts it into a dataverse DataTable.
     *
     * @param stream a <code>BufferedInputStream</code>.
     * @param ignored
     * @return an <code>TabularDataIngest</code> object
     * @throws java.io.IOException if a reading error occurs.
     */
    @Override
    public TabularDataIngest read(BufferedInputStream stream, File dataFile) throws IOException {
        init();
        
        TabularDataIngest ingesteddata = new TabularDataIngest();
        DataTable dataTable = new DataTable();

        BufferedReader localBufferedReader = new BufferedReader(new InputStreamReader(stream));

        File tabFileDestination = File.createTempFile("data-", ".tab");
        PrintWriter tabFileWriter = new PrintWriter(tabFileDestination.getAbsolutePath());

        int lineCount = readFile(localBufferedReader, dataTable, tabFileWriter);
        
        dataTable.setCaseQuantity(new Long(lineCount));
        
        
        ingesteddata.setTabDelimitedFile(tabFileDestination);
        ingesteddata.setDataTable(dataTable);
        return ingesteddata;

    }

    public int readFile(BufferedReader csvReader, DataTable dataTable, PrintWriter pwout) throws IOException {
        dbglog.warning("RTabFileParser: Inside R Tab file parser");

        String line;
        String[] valueTokens;

        int lineCounter = 0;

        // Read first line: 
        
        line = csvReader.readLine();
        line = line.replaceFirst("[\r\n]*$", "");
        valueTokens = line.split("" + delimiterChar, -2);
        
        if (valueTokens == null || valueTokens.length < 1) {
            throw new IOException("Failed to read first, variable name line of the CSV file.");
        }

        int varQnty = valueTokens.length;
        
        // Create variables: 
        
        List<DataVariable> variableList = new ArrayList<DataVariable>();
        
        for (int i = 0; i < varQnty; i++) {
            String varName = valueTokens[i];
            
            if (varName == null || varName.equals("")) {
                // TODO: 
                // Add a sensible variable name validation algorithm.
                // -- L.A. 4.0 alpha 1
                throw new IOException ("Invalid variable names in the first line!");
            }
            
            DataVariable dv = new DataVariable();
            dv.setName(varName);
            dv.setLabel(varName);
            dv.setInvalidRanges(new ArrayList());
            dv.setSummaryStatistics(new ArrayList());
            dv.setUnf("UNF:6:NOTCALCULATED");
            dv.setCategories(new ArrayList());
            variableList.add(dv);

            dv.setVariableFormatType(varService.findVariableFormatTypeByName("character"));
            dv.setVariableIntervalType(varService.findVariableIntervalTypeByName("discrete"));
            
            dv.setFileOrder(varQnty);
            dv.setDataTable(dataTable);
        }
        
        dataTable.setVarQuantity(new Long(varQnty));
        dataTable.setDataVariables(variableList);
        
        boolean[] isNumericVariable = new boolean[varQnty]; 

        String[] caseRow = new String[varQnty];
        
        while ((line = csvReader.readLine()) != null) {
            // chop the line:
            line = line.replaceFirst("[\r\n]*$", "");
            valueTokens = line.split("" + delimiterChar, -2);

            if (valueTokens == null) {
                throw new IOException("Failed to read line " + (lineCounter + 1) + " of the Data file.");
            }

            if (valueTokens.length != varQnty) {
                throw new IOException("Reading mismatch, line " + (lineCounter + 1) + " of the Data file: "
                        + varQnty + " delimited values expected, " + valueTokens.length + " found.");
            }

            for (int i = 0; i < varQnty; i++) {
                // In this pass, assume that everything is a String. 
                // Strings are stored in tab files quoted; 
                // Missing values are stored as tab-delimited nothing; 
                // Empty strings stored as "" (quoted nothing):

                if (valueTokens[i] != null && (!valueTokens[i].equals(""))) {
                    String charToken = valueTokens[i];
                    // Dealing with quotes: 
                    // remove the leading and trailing quotes, if present:
                    charToken = charToken.replaceFirst("^\"", "");
                    charToken = charToken.replaceFirst("\"$", "");
                    // escape the remaining ones:
                    charToken = charToken.replace("\"", "\\\"");
                    // final pair of quotes:
                    charToken = "\"" + charToken + "\"";
                    caseRow[i] = charToken;
                } else {
                    caseRow[i] = "";
                }
            }

            pwout.println(StringUtils.join(caseRow, "\t"));
            lineCounter++;
        }

        pwout.close();
        return lineCounter;
    }

}
