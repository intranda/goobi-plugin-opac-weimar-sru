package de.intranda.goobi.plugins.sru;

import java.io.IOException;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.util.Iterator;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.log4j.Logger;
import org.goobi.production.plugin.interfaces.IOpacPlugin;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.Namespace;
import org.jdom2.input.DOMBuilder;
import org.jdom2.input.SAXBuilder;
import org.w3c.dom.Node;
import org.w3c.dom.Text;

import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.DocStructType;
import ugh.dl.Fileformat;
import ugh.dl.Prefs;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.TypeNotAllowedAsChildException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.fileformats.mets.XStream;
import ugh.fileformats.opac.PicaPlus;

import com.googlecode.fascinator.redbox.sru.SRUClient;

import de.intranda.goobi.plugins.HaabSruOpacImport;
import de.sub.goobi.helper.UghHelper;

public class SRUHelper {
    private static final Namespace SRW = Namespace.getNamespace("srw", "http://www.loc.gov/zing/srw/");

    private static final Namespace PICA = Namespace.getNamespace("pica", "info:srw/schema/5/picaXML-v1.0");

    private static final Logger logger = Logger.getLogger(SRUHelper.class);
    
    // private static final Namespace DC = Namespace.getNamespace("dc", "http://purl.org/dc/elements/1.1/");
    // private static final Namespace DIAG = Namespace.getNamespace("diag", "http://www.loc.gov/zing/srw/diagnostic/");
    // private static final Namespace XCQL = Namespace.getNamespace("xcql", "http://www.loc.gov/zing/cql/xcql/");

    public static String search(String catalogue, String searchField, String searchValue) {
        SRUClient client;
        try {
            client = new SRUClient(catalogue, "picaxml", null, null);
            return client.getSearchResponse(searchField + "=" + searchValue);
        } catch (MalformedURLException e) {
        }
        return "";
    }

    @SuppressWarnings("deprecation")
    public static Node parseResult(HaabSruOpacImport opac, String catalogue, String resultString) throws IOException, JDOMException,
            ParserConfigurationException {
        // removed validation against external dtd
        SAXBuilder builder = new SAXBuilder(false);
        builder.setValidation(false);
        builder.setFeature("http://xml.org/sax/features/validation", false);
        builder.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false);
        builder.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        Document doc = builder.build(new StringReader(resultString));
        // srw:searchRetrieveResponse
        Element root = doc.getRootElement();
        // <srw:records>
        Element srw_records = root.getChild("records", SRW);
        // <srw:record>
        Element srw_record = srw_records.getChild("record", SRW);
        // <srw:recordData>
        if (srw_record != null) {

            Element recordData = srw_record.getChild("recordData", SRW);
            List<Element> recordList = recordData.getChildren("record", PICA);
            Element record = null;
            if (recordList == null || recordList.size() == 0) {
                opac.setHitcount(0);
                return null;
            } else {
                opac.setHitcount(recordList.size());
                record = recordData.getChild("record", PICA);
            }

            record = recordData.getChild("record", PICA);

            // generate an answer document
            DocumentBuilderFactory dbfac = DocumentBuilderFactory.newInstance();
            DocumentBuilder docBuilder = dbfac.newDocumentBuilder();
            org.w3c.dom.Document answer = docBuilder.newDocument();
            org.w3c.dom.Element collection = answer.createElement("collection");
            answer.appendChild(collection);

            org.w3c.dom.Element picaRecord = answer.createElement("record");

            boolean isMultiVolume = false;
            String anchorIdentifier = "";
            List<Element> data = record.getChildren();

            for (Element datafield : data) {
                if (datafield.getAttributeValue("tag") != null) {
                    String tag = datafield.getAttributeValue("tag");
                    org.w3c.dom.Element field = answer.createElement("field");
                    picaRecord.appendChild(field);
                    if (datafield.getAttributeValue("occurrence") != null) {
                        field.setAttribute("occurrence", datafield.getAttributeValue("occurrence"));
                    }
                    field.setAttribute("tag", tag);
                    List<Element> subfields = datafield.getChildren();
                    for (Element sub : subfields) {
                        org.w3c.dom.Element subfield = answer.createElement("subfield");
                        field.appendChild(subfield);
                        String code = sub.getAttributeValue("code");
                        subfield.setAttribute("code", code);
                        Text text = answer.createTextNode(sub.getText());
                        subfield.appendChild(text);
                        if (tag.equals("036D") && code.equals("9")) {
                            isMultiVolume = true;
                            anchorIdentifier = sub.getText();
                        } else if (tag.equals("002@") && code.equals("0")) {
                            opac.setGattung(sub.getText());
                        }
                    }
                }
            }

            if (isMultiVolume) {
                String anchorResult = SRUHelper.search(catalogue, "pica.ppn", anchorIdentifier);
                Document anchorDoc = new SAXBuilder().build(new StringReader(anchorResult), "utf-8");

                // srw:searchRetrieveResponse
                Element anchorRoot = anchorDoc.getRootElement();
                // <srw:records>
                Element anchorSrw_records = anchorRoot.getChild("records", SRW);
                // <srw:record>
                Element anchorSrw_record = anchorSrw_records.getChild("record", SRW);
                // <srw:recordData>
                if (anchorSrw_record != null) {
                    Element anchorRecordData = anchorSrw_record.getChild("recordData", SRW);
                    Element anchorRecord = anchorRecordData.getChild("record", PICA);

                    org.w3c.dom.Element anchorPicaRecord = answer.createElement("record");
                    collection.appendChild(anchorPicaRecord);

                    List<Element> anchorData = anchorRecord.getChildren();
                    for (Element datafield : anchorData) {
                        if (datafield.getAttributeValue("tag") != null) {
                            String tag = datafield.getAttributeValue("tag");
                            org.w3c.dom.Element field = answer.createElement("field");
                            anchorPicaRecord.appendChild(field);
                            if (datafield.getAttributeValue("occurrence") != null) {
                                field.setAttribute("occurrence", datafield.getAttributeValue("occurrence"));
                            }
                            field.setAttribute("tag", tag);
                            List<Element> subfields = datafield.getChildren();
                            for (Element sub : subfields) {
                                org.w3c.dom.Element subfield = answer.createElement("subfield");
                                field.appendChild(subfield);
                                String code = sub.getAttributeValue("code");
                                subfield.setAttribute("code", code);
                                Text text = answer.createTextNode(sub.getText());
                                subfield.appendChild(text);
                            }
                        }
                    }

                }
                
            }
            collection.appendChild(picaRecord);
            return answer.getDocumentElement();
        }
        return null;
    }

    public static Fileformat parsePicaFormat(HaabSruOpacImport opac, Node pica, Prefs prefs) throws ReadException, PreferencesException, TypeNotAllowedForParentException {

        PicaPlus pp = new PicaPlus(prefs);
        pp.read(pica);
        DigitalDocument dd = pp.getDigitalDocument();
        Fileformat ff = new XStream(prefs);
        ff.setDigitalDocument(dd);
        /* BoundBook hinzufügen */
        DocStructType dst = prefs.getDocStrctTypeByName("BoundBook");
        DocStruct dsBoundBook = dd.createDocStruct(dst);
        dd.setPhysicalDocStruct(dsBoundBook);
        
        checkResult(opac,dd, prefs, pica);
        
        return ff;

    }
    
    
    
    public static void checkResult(HaabSruOpacImport opac, DigitalDocument inDigDoc, Prefs inPrefs, Node pica) {
        Document myJdomDoc = new DOMBuilder().build(pica.getOwnerDocument());
        Element myFirstHit = myJdomDoc.getRootElement().getChild("record");
        
        checkMyOpacResult(opac, inDigDoc, inPrefs, myFirstHit);

    }
    
    public static void checkMyOpacResult(IOpacPlugin opac, DigitalDocument inDigDoc, Prefs inPrefs, Element myFirstHit) {
        
        UghHelper ughhelp = new UghHelper();
        DocStruct topstruct = inDigDoc.getLogicalDocStruct();
        DocStruct boundbook = inDigDoc.getPhysicalDocStruct();
        DocStruct topstructChild = null;
        Element mySecondHit = null;
        
        /*
         * -------------------------------- bei Multivolumes noch das Child in xml und docstruct ermitteln --------------------------------
         */
        // if (isMultivolume()) {
        if (opac.getOpacDocType().isMultiVolume()) {
            try {
                topstructChild = topstruct.getAllChildren().get(0);
            } catch (RuntimeException e) {
            }
            mySecondHit = (Element) myFirstHit.getParentElement().getChildren().get(1);
        }

        /*
         * -------------------------------- vorhandene PPN als digitale oder analoge einsetzen --------------------------------
         */
        String epn = getElementFieldValue(myFirstHit, "203@", "0");
        ughhelp.replaceMetadatum(topstruct, inPrefs, "CatalogIDDigital", "");
        if (opac.getGattung().toLowerCase().startsWith("o")) {
            ughhelp.replaceMetadatum(topstruct, inPrefs, "CatalogIDDigital", epn);
        } else {
            ughhelp.replaceMetadatum(topstruct, inPrefs, "CatalogIDSource", epn);
        }

        /*
         * -------------------------------- wenn es ein multivolume ist, dann auch die PPN prüfen --------------------------------
         */
        if (topstructChild != null && mySecondHit != null) {
            String secondHitepn = getElementFieldValue(mySecondHit, "203@", "0");
            ughhelp.replaceMetadatum(topstructChild, inPrefs, "CatalogIDDigital", "");
            if (opac.getGattung().toLowerCase().startsWith("o")) {
                ughhelp.replaceMetadatum(topstructChild, inPrefs, "CatalogIDDigital", secondHitepn);
            } else {
                ughhelp.replaceMetadatum(topstructChild, inPrefs, "CatalogIDSource", secondHitepn);
            }
        }

        
        String ppn = getElementFieldValue(myFirstHit, "003@", "0");
        ughhelp.replaceMetadatum(topstruct, inPrefs, "CatalogIDPicaPPNDigital", "");
        if (opac.getGattung().toLowerCase().startsWith("o")) {
            ughhelp.replaceMetadatum(topstruct, inPrefs, "CatalogIDPicaPPNDigital", ppn);
        } else {
            ughhelp.replaceMetadatum(topstruct, inPrefs, "CatalogIDPicaPPNSource", ppn);
        }

        /*
         * -------------------------------- wenn es ein multivolume ist, dann auch die PPN prüfen --------------------------------
         */
        if (topstructChild != null && mySecondHit != null) {
            String secondHitppn = getElementFieldValue(mySecondHit, "003@", "0");
            ughhelp.replaceMetadatum(topstructChild, inPrefs, "CatalogIDPicaPPNDigital", "");
            if (opac.getGattung().toLowerCase().startsWith("o")) {
                ughhelp.replaceMetadatum(topstructChild, inPrefs, "CatalogIDPicaPPNDigital", secondHitppn);
            } else {
                ughhelp.replaceMetadatum(topstructChild, inPrefs, "CatalogIDPicaPPNSource", secondHitppn);
            }
        }

        /*
         * -------------------------------- den Main-Title bereinigen --------------------------------
         */
        String myTitle = getElementFieldValue(myFirstHit, "021A", "a");
        /*
         * wenn der Fulltittle nicht in dem Element stand, dann an anderer Stelle nachsehen (vor allem bei Contained-Work)
         */
        if (myTitle == null || myTitle.length() == 0) {
            myTitle = getElementFieldValue(myFirstHit, "021B", "a");
        }
        if (myTitle == null || myTitle.length() == 0) {
            myTitle = getElementFieldValue(myFirstHit, "036F", "8");
        }
        if (myTitle == null || myTitle.length() == 0) {
            myTitle = getElementFieldValue(myFirstHit, "027D", "a");
        }
      
        ughhelp.replaceMetadatum(topstruct, inPrefs, "TitleDocMain", myTitle.replaceAll("@", ""));

        /*
         * -------------------------------- Sorting-Titel mit Umlaut-Konvertierung --------------------------------
         */
        if (myTitle.indexOf("@") != -1) {
            myTitle = myTitle.substring(myTitle.indexOf("@") + 1);
        }
        ughhelp.replaceMetadatum(topstruct, inPrefs, "TitleDocMainShort", myTitle);

        /*
         * -------------------------------- bei multivolumes den Main-Title bereinigen --------------------------------
         */
        String fulltitleMulti = null;
        if (topstructChild != null && mySecondHit != null) {
            
            fulltitleMulti = getElementFieldValue(mySecondHit, "021A", "a").replaceAll("@", "");
            if (fulltitleMulti == null || fulltitleMulti.length() == 0) {
                fulltitleMulti = getElementFieldValue(mySecondHit, "021B", "a");
            }
            if (fulltitleMulti == null || fulltitleMulti.length() == 0) {
                fulltitleMulti = getElementFieldValue(mySecondHit, "036F", "8");
            }
            if (fulltitleMulti == null || fulltitleMulti.length() == 0) {
                fulltitleMulti = getElementFieldValue(mySecondHit, "027D", "a");
            }
            if (fulltitleMulti == null || fulltitleMulti.length() == 0) {
                fulltitleMulti = myTitle;
            }
            
            
            ughhelp.replaceMetadatum(topstructChild, inPrefs, "TitleDocMain", fulltitleMulti);
        }

        /*
         * -------------------------------- bei multivolumes den Sorting-Titel mit Umlaut-Konvertierung --------------------------------
         */
        if (topstructChild != null && mySecondHit != null && fulltitleMulti != null) {
            String sortingTitleMulti = fulltitleMulti;
            if (sortingTitleMulti.indexOf("@") != -1) {
                sortingTitleMulti = sortingTitleMulti.substring(sortingTitleMulti.indexOf("@") + 1);
            }
            ughhelp.replaceMetadatum(topstructChild, inPrefs, "TitleDocMainShort", sortingTitleMulti);
            // sortingTitle = sortingTitleMulti;
        }
        

        /*
         * -------------------------------- Sprachen - Konvertierung auf zwei Stellen --------------------------------
         */
        String sprache = getElementFieldValue(myFirstHit, "010@", "a");
        sprache = ughhelp.convertLanguage(sprache);
        ughhelp.replaceMetadatum(topstruct, inPrefs, "DocLanguage", sprache);

        /*
         * -------------------------------- bei multivolumes die Sprachen - Konvertierung auf zwei Stellen --------------------------------
         */
        if (topstructChild != null && mySecondHit != null) {
            String spracheMulti = getElementFieldValue(mySecondHit, "010@", "a");
            spracheMulti = ughhelp.convertLanguage(spracheMulti);
            ughhelp.replaceMetadatum(topstructChild, inPrefs, "DocLanguage", spracheMulti);
        }

        /*
         * -------------------------------- ISSN --------------------------------
         */
        String issn = getElementFieldValue(myFirstHit, "005A", "0");
        ughhelp.replaceMetadatum(topstruct, inPrefs, "ISSN", issn);

        /*
         * -------------------------------- Copyright --------------------------------
         */
        String copyright = getElementFieldValue(myFirstHit, "037I", "a");
        ughhelp.replaceMetadatum(boundbook, inPrefs, "copyrightimageset", copyright);

        /*
         * -------------------------------- Format --------------------------------
         */
        String format = getElementFieldValue(myFirstHit, "034I", "a");
        ughhelp.replaceMetadatum(boundbook, inPrefs, "FormatSourcePrint", format);

        /*
         * -------------------------------- Umfang --------------------------------
         */
        String umfang = getElementFieldValue(myFirstHit, "034D", "a");
        ughhelp.replaceMetadatum(topstruct, inPrefs, "SizeSourcePrint", umfang);

        /*
         * -------------------------------- Signatur --------------------------------
         */
        String sig = getElementFieldValue(myFirstHit, "209A", "c");
        if (sig.length() > 0) {
            sig = "<" + sig + ">";
        }
        sig += getElementFieldValue(myFirstHit, "209A", "f") + " ";
        sig += getElementFieldValue(myFirstHit, "209A", "a");
        ughhelp.replaceMetadatum(boundbook, inPrefs, "shelfmarksource", sig.trim());
        if (sig.trim().length() == 0) {
            logger.debug("Signatur part 1: " + sig);
            logger.debug(myFirstHit.getChildren());
            sig = getElementFieldValue(myFirstHit, "209A/01", "c");
            if (sig.length() > 0) {
                sig = "<" + sig + ">";
            }
            sig += getElementFieldValue(myFirstHit, "209A/01", "f") + " ";
            sig += getElementFieldValue(myFirstHit, "209A/01", "a");
            if (mySecondHit != null) {
                sig += getElementFieldValue(mySecondHit, "209A", "f") + " ";
                sig += getElementFieldValue(mySecondHit, "209A", "a");
            }
            ughhelp.replaceMetadatum(boundbook, inPrefs, "shelfmarksource", sig.trim());
        }
        logger.debug("Signatur full: " + sig);

        /*
         * -------------------------------- Ats Tsl Vorbereitung --------------------------------
         */
        myTitle = myTitle.toLowerCase();
        myTitle = myTitle.replaceAll("&", "");

        /*
         * -------------------------------- bei nicht-Zeitschriften Ats berechnen --------------------------------
         */
        // if (!gattung.startsWith("ab") && !gattung.startsWith("ob")) {
        String autor = getElementFieldValue(myFirstHit, "028A", "a").toLowerCase();
        if (autor == null || autor.equals("")) {
            autor = getElementFieldValue(myFirstHit, "028A", "8").toLowerCase();
        }
       opac.setAtstsl(opac.createAtstsl(myTitle, autor));

        /*
         * -------------------------------- bei Zeitschriften noch ein PeriodicalVolume als Child einfügen --------------------------------
         */
        // if (isPeriodical()) {
        if (opac.getOpacDocType().isPeriodical()) {
            try {
                DocStructType dstV = inPrefs.getDocStrctTypeByName("PeriodicalVolume");
                DocStruct dsvolume = inDigDoc.createDocStruct(dstV);
                topstruct.addChild(dsvolume);
            } catch (TypeNotAllowedForParentException e) {
                e.printStackTrace();
            } catch (TypeNotAllowedAsChildException e) {
                e.printStackTrace();
            }
        }

    }
    
    public static String getElementFieldValue(Element myFirstHit, String inFieldName, String inAttributeName) {

        for (Iterator<Element> iter2 = myFirstHit.getChildren().iterator(); iter2.hasNext();) {
            Element myElement = iter2.next();
            String feldname = myElement.getAttributeValue("tag");
            /*
             * wenn es das gesuchte Feld ist, dann den Wert mit dem passenden Attribut zurückgeben
             */
            if (feldname.equals(inFieldName)) {
                return getFieldValue(myElement, inAttributeName);
            }
        }
        return "";
    }
    
    public static String getFieldValue(Element inElement, String attributeValue) {
        String rueckgabe = "";

        for (Iterator<Element> iter = inElement.getChildren().iterator(); iter.hasNext();) {
            Element subElement = iter.next();
            if (subElement.getAttributeValue("code").equals(attributeValue)) {
                rueckgabe = subElement.getValue();
            }
        }
        return rueckgabe;
    }
}
