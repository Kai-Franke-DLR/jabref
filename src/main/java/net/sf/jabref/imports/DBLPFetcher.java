/*  Copyright (C) 2011 Sascha Hunold.
    This program is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 2 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License along
    with this program; if not, write to the Free Software Foundation, Inc.,
    51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
*/
package net.sf.jabref.imports;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JPanel;

import net.sf.jabref.BibtexEntry;
import net.sf.jabref.GUIGlobals;
import net.sf.jabref.OutputPrinter;

public class DBLPFetcher implements EntryFetcher {


    private final static String URL_START = "http://www.dblp.org/search/api/";
    private final static String URL_PART1 = "?q=";
    private final static String URL_END   = "&h=1000&c=4&f=0&format=json";

	private volatile boolean shouldContinue = false;
	private String query;
	private final DBLPHelper helper = new DBLPHelper();


	@Override
	public void stopFetching() {
	    shouldContinue  = false;
	}

	@Override
	public boolean processQuery(String query, ImportInspector inspector,
			OutputPrinter status) {

		boolean res = false;
		this.query = query;

		shouldContinue = true;

		try {

			String address = makeSearchURL();
			//System.out.println(address);
			URL url = new URL(address);
	        String page = readFromURL(url);

	        //System.out.println(page);
	        String[] lines = page.split("\n");
	        List<String> bibtexUrlList = new ArrayList<String>();
	        for(final String line : lines) {
	        	if( line.startsWith("\"url\"") ) {
	        		String addr = line.replace("\"url\":\"", "");
	        		addr = addr.substring(0, addr.length()-2);
	        		//System.out.println("key address: " + addr);
	        		bibtexUrlList.add(addr);
	        	}
	        }


	        // 2014-11-08
	        // DBLP now shows the BibTeX entry using ugly HTML entities
	        // but they also offer the download of a bib file
	        // we find this in the page which we get from "url"
	        // and this bib file is then in "biburl"

	        int count = 1;
	        for(String urlStr : bibtexUrlList) {
	        	if( ! shouldContinue ) {
	        		break;
	        	}

	        	final URL bibUrl = new URL(urlStr);

		        final String bibtexHTMLPage = readFromURL(bibUrl);

		        final String[] htmlLines = bibtexHTMLPage.split("\n");

		        for(final String line : htmlLines) {
		        	if( line.contains("biburl") ) {
		        		int sidx = line.indexOf("{");
		        		int eidx = line.indexOf("}");
		        		// now we take everything within the curley braces
		        		String bibtexUrl = line.substring(sidx+1, eidx);

		        		// we do not access dblp.uni-trier.de as they will complain
		        		bibtexUrl = bibtexUrl.replace("dblp.uni-trier.de", "www.dblp.org");

			        	final URL bibFileURL = new URL(bibtexUrl);
			        	//System.out.println("URL:|"+bibtexUrl+"|");
				        final String bibtexPage = readFromURL(bibFileURL);

				        BibtexEntry bibtexEntry = BibtexParser.singleFromString(bibtexPage);

			        	inspector.addEntry(bibtexEntry);
			        	inspector.setProgress(count, bibtexUrlList.size());
			        	count++;
		        	}
		        }

	        }

	        // everything went smooth
	        res = true;

		} catch (IOException e) {
			e.printStackTrace();
			status.showMessage(e.getMessage());
		}

        return res;
	}


    private String readFromURL(final URL source) throws IOException {
        final InputStream in = source.openStream();
        final InputStreamReader ir = new InputStreamReader(in);
        final StringBuffer sbuf = new StringBuffer();

        char[] cbuf = new char[256];
        int read;
        while( (read = ir.read(cbuf)) != -1 ) {
        	sbuf.append(cbuf, 0, read);
        }
        return sbuf.toString();
    }

	private String makeSearchURL() {
        StringBuffer sb = new StringBuffer(URL_START).append(URL_PART1);
        String cleanedQuery = helper.cleanDBLPQuery(query);
        sb.append(cleanedQuery);
        sb.append(URL_END);
        return sb.toString();
	}

	@Override
	public String getTitle() {
		return "DBLP";
	}

	@Override
	public String getKeyName() {
		return "DBLP";
	}

	@Override
	public URL getIcon() {
	    return GUIGlobals.getIconUrl("www");
	}

	@Override
	public String getHelpPage() {
		return null;
	}

	@Override
	public JPanel getOptionsPanel() {
		return null;
	}

}
