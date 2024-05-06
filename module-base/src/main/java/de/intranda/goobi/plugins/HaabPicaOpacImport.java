/**
 * This file is part of the pica opac import plugin for the Goobi Application - a Workflow tool for the support of mass digitization.
 * 
 * Visit the websites for more information. 
 *          - http://digiverso.com 
 *          - http://www.intranda.com
 * 
 * Copyright 2011 - 2013, intranda GmbH, Göttingen
 * 
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the  GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 * 
 */
package de.intranda.goobi.plugins;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Iterator;

import net.xeoh.plugins.base.annotations.PluginImplementation;

import org.apache.log4j.Logger;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IOpacPlugin;
import org.jdom2.Attribute;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.input.DOMBuilder;
import org.jdom2.output.DOMOutputter;
import org.w3c.dom.Node;

import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.DocStructType;
import ugh.dl.Fileformat;
import ugh.dl.Prefs;
import ugh.fileformats.mets.XStream;
import ugh.fileformats.opac.PicaPlus;
import de.intranda.goobi.plugins.sru.SRUHelper;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.UghHelper;
import de.unigoettingen.sub.search.opac.Catalogue;
import de.unigoettingen.sub.search.opac.ConfigOpac;
import de.unigoettingen.sub.search.opac.ConfigOpacCatalogue;
import de.unigoettingen.sub.search.opac.ConfigOpacDoctype;
import de.unigoettingen.sub.search.opac.GetOpac;
import de.unigoettingen.sub.search.opac.Query;

@PluginImplementation
public class HaabPicaOpacImport implements IOpacPlugin {
    protected static final Logger myLogger = Logger.getLogger(HaabPicaOpacImport.class);

    protected int hitcount;
    protected String gattung = "Aa";
    protected String atstsl;
    ConfigOpacCatalogue coc;
    private boolean verbose = true;

    /* (non-Javadoc)
     * @see de.sub.goobi.Import.IOpac#OpacToDocStruct(java.lang.String, java.lang.String, java.lang.String, ugh.dl.Prefs, boolean)
     */
    @Override
    public Fileformat search(String inSuchfeld, String inSuchbegriff, ConfigOpacCatalogue catalogue, Prefs inPrefs) throws Exception {
        /*
         * -------------------------------- Katalog auswählen --------------------------------
         */
        coc = catalogue;
        if (this.coc == null) {
            throw new IOException("Catalogue not found: " + coc.getTitle() + ", please check Configuration in goobi_opac.xml");
        }
        Catalogue cat = new Catalogue(this.coc.getDescription(), this.coc.getAddress(), this.coc.getPort(), this.coc.getCbs(), this.coc
                .getDatabase());
        cat.setProtocol(coc.getProtocol());
        if (verbose) {
            Helper.setMeldung(null, Helper.getTranslation("CatalogueUsage") + ": ", this.coc.getDescription());
        }
        GetOpac myOpac = new GetOpac(cat);
        myOpac.setData_character_encoding(this.coc.getCharset());
        Query myQuery = new Query(inSuchbegriff, inSuchfeld);
        /* im Notfall ohne Treffer sofort aussteigen */
        this.hitcount = myOpac.getNumberOfHits(myQuery);
        if (this.hitcount == 0) {
            return null;
        }

        /*
         * -------------------------------- Opac abfragen und erhaltenes Dom-Dokument in JDom-Dokument umwandeln --------------------------------
         */
        Node myHitlist = myOpac.retrievePicaNode(myQuery, 1);
        /* Opac-Beautifier aufrufen */
        myHitlist = this.coc.executeBeautifier(myHitlist);
        Document myJdomDoc = new DOMBuilder().build(myHitlist.getOwnerDocument());
        Element myFirstHit = myJdomDoc.getRootElement().getChild("record");

        /* von dem Treffer den Dokumententyp ermitteln */
        this.gattung = getGattung(myFirstHit);

        myLogger.debug("Gattung: " + this.gattung);
        /*
         * -------------------------------- wenn der Treffer ein Volume eines Multivolume-Bandes ist, dann das Sammelwerk überordnen
         * --------------------------------
         */
        // if (isMultivolume()) {
        if (getOpacDocType().isMultiVolume()) {
            /* Sammelband-PPN ermitteln */
            String multiVolumePpn = getPpnFromParent(myFirstHit, "036D", "9");
            if (multiVolumePpn != "") {
                /* Sammelband aus dem Opac holen */

                myQuery = new Query(multiVolumePpn, "12");
                /* wenn ein Treffer des Parents im Opac gefunden wurde */
                if (myOpac.getNumberOfHits(myQuery) == 1) {
                    Node myParentHitlist = myOpac.retrievePicaNode(myQuery, 1);
                    /* Opac-Beautifier aufrufen */
                    myParentHitlist = this.coc.executeBeautifier(myParentHitlist);
                    /* Konvertierung in jdom-Elemente */
                    Document myJdomDocMultivolumeband = new DOMBuilder().build(myParentHitlist.getOwnerDocument());

                    /* Testausgabe */
                    // XMLOutputter outputter = new XMLOutputter();
                    // FileOutputStream output = new
                    // FileOutputStream("D:/fileParent.xml");
                    // outputter.output(myJdomDocMultivolumeband.getRootElement(),
                    // output);
                    /* dem Rootelement den Volume-Treffer hinzufügen */
                    myFirstHit.getParent().removeContent(myFirstHit);
                    myJdomDocMultivolumeband.getRootElement().addContent(myFirstHit);

                    /* Testausgabe */
                    // output = new FileOutputStream("D:/fileFull.xml");
                    // outputter.output(myJdomDocMultivolumeband.getRootElement(),
                    // output);
                    myJdomDoc = myJdomDocMultivolumeband;
                    myFirstHit = myJdomDoc.getRootElement().getChild("record");

                    /* die Jdom-Element wieder zurück zu Dom konvertieren */
                    DOMOutputter doutputter = new DOMOutputter();
                    myHitlist = doutputter.output(myJdomDocMultivolumeband);
                    /*
                     * dabei aber nicht das Document, sondern das erste Kind nehmen
                     */
                    myHitlist = myHitlist.getFirstChild();
                }
            }
        }

        /*
         * -------------------------------- wenn der Treffer ein Contained Work ist, dann übergeordnetes Werk --------------------------------
         */
        // if (isContainedWork()) {
        if (getOpacDocType().isContainedWork()) {
            /* PPN des übergeordneten Werkes ermitteln */
            String ueberGeordnetePpn = getPpnFromParent(myFirstHit, "021A", "9");
            if (ueberGeordnetePpn != "") {
                /* Sammelband aus dem Opac holen */
                myQuery = new Query(ueberGeordnetePpn, "12");
                /* wenn ein Treffer des Parents im Opac gefunden wurde */
                if (myOpac.getNumberOfHits(myQuery) == 1) {
                    Node myParentHitlist = myOpac.retrievePicaNode(myQuery, 1);
                    /* Opac-Beautifier aufrufen */
                    myParentHitlist = this.coc.executeBeautifier(myParentHitlist);
                    /* Konvertierung in jdom-Elemente */
                    Document myJdomDocParent = new DOMBuilder().build(myParentHitlist.getOwnerDocument());
                    Element myFirstHitParent = myJdomDocParent.getRootElement().getChild("record");
                    /* Testausgabe */
                    // XMLOutputter outputter = new XMLOutputter();
                    // FileOutputStream output = new
                    // FileOutputStream("D:/fileParent.xml");
                    // outputter.output(myJdomDocParent.getRootElement(),
                    // output);
                    /*
                     * alle Elemente des Parents übernehmen, die noch nicht selbst vorhanden sind
                     */
                    if (myFirstHitParent.getChildren() != null) {

                        for (Iterator<Element> iter = myFirstHitParent.getChildren().iterator(); iter.hasNext();) {
                            Element ele = iter.next();
                            if (getElementFromChildren(myFirstHit, ele.getAttributeValue("tag")) == null) {
                                myFirstHit.getChildren().add(getCopyFromJdomElement(ele));
                            }
                        }
                    }
                }
            }
        }

        /*
         * -------------------------------- aus Opac-Ergebnis RDF-Datei erzeugen --------------------------------
         */
        /* XML in Datei schreiben */
        //                XMLOutputter outputter = new XMLOutputter();
        //                FileOutputStream output = new FileOutputStream("/home/robert/temp_opac.xml");
        //                outputter.output(myJdomDoc.getRootElement(), output);

        /* myRdf temporär in Datei schreiben */
        // myRdf.write("D:/temp.rdf.xml");

        /* zugriff auf ugh-Klassen */
        PicaPlus pp = new PicaPlus(inPrefs);
        pp.read(myHitlist);
        DigitalDocument dd = pp.getDigitalDocument();
        Fileformat ff = new XStream(inPrefs);
        ff.setDigitalDocument(dd);
        /* BoundBook hinzufügen */
        DocStructType dst = inPrefs.getDocStrctTypeByName("BoundBook");
        DocStruct dsBoundBook = dd.createDocStruct(dst);
        dd.setPhysicalDocStruct(dsBoundBook);
        /* Inhalt des RDF-Files überprüfen und ergänzen */
        SRUHelper.checkMyOpacResult(this, ff.getDigitalDocument(), inPrefs, myFirstHit, inSuchbegriff);

        // rdftemp.write("D:/PicaRdf.xml");
        return ff;
    }

    /**
     * DocType (Gattung) ermitteln
     * 
     * @param inHit
     * @return
     */
    public String getGattung(Element inHit) {

        for (Iterator<Element> iter = inHit.getChildren().iterator(); iter.hasNext();) {
            Element tempElement = iter.next();
            String feldname = tempElement.getAttributeValue("tag");
            // System.out.println(feldname);
            if (feldname.equals("002@")) {
                return getSubelementValue(tempElement, "0");
            }
        }
        return "";
    }

    public String getSubelementValue(Element inElement, String attributeValue) {
        String rueckgabe = "";

        for (Iterator<Element> iter = inElement.getChildren().iterator(); iter.hasNext();) {
            Element subElement = iter.next();
            if (subElement.getAttributeValue("code").equals(attributeValue)) {
                rueckgabe = subElement.getValue();
            }
        }
        return rueckgabe;
    }

    /**
     * die PPN des übergeordneten Bandes (MultiVolume: 036D-9 und ContainedWork: 021A-9) ermitteln
     * 
     * @param inElement
     * @return
     */
    public String getPpnFromParent(Element inHit, String inFeldName, String inSubElement) {
        for (Iterator<Element> iter = inHit.getChildren().iterator(); iter.hasNext();) {
            Element tempElement = iter.next();
            String feldname = tempElement.getAttributeValue("tag");
            // System.out.println(feldname);
            if (feldname.equals(inFeldName)) {
                return getSubelementValue(tempElement, inSubElement);
            }
        }
        return "";
    }

    /* (non-Javadoc)
     * @see de.sub.goobi.Import.IOpac#getHitcount()
     */
    @Override
    public int getHitcount() {
        return this.hitcount;
    }

    /* (non-Javadoc)
     * @see de.sub.goobi.Import.IOpac#createAtstsl(java.lang.String, java.lang.String)
     */

    public String createAtstsl(String myTitle, String autor) {
        String titleValue = "";
        if (myTitle != null && !myTitle.isEmpty()) {
            if (myTitle.contains(" ")) {
                titleValue = myTitle.substring(0, myTitle.indexOf(" "));
            } else {
                titleValue = myTitle;
            }
        }
        titleValue = titleValue.toLowerCase();
        if (titleValue.length() > 6) {
            atstsl = titleValue.substring(0, 6);
        } else {
            atstsl = titleValue;
        }
        atstsl = UghHelper.convertUmlaut(atstsl);
        atstsl = atstsl.replaceAll("[\\W]", "");
        return atstsl;
    }

    protected String convertUmlaut(String inString) {
        /* Pfad zur Datei ermitteln */
        String filename = ConfigurationHelper.getInstance().getConfigurationFolder() + "goobi_opacUmlaut.txt";
        //      }
        /* Datei zeilenweise durchlaufen und die Sprache vergleichen */
        try {
            FileInputStream fis = new FileInputStream(filename);
            InputStreamReader isr = new InputStreamReader(fis, "UTF8");
            BufferedReader in = new BufferedReader(isr);
            String str;
            while ((str = in.readLine()) != null) {
                if (str.length() > 0) {
                    inString = inString.replaceAll(str.split(" ")[0], str.split(" ")[1]);
                }
            }
            in.close();
        } catch (IOException e) {
            myLogger.error("IOException bei Umlautkonvertierung", e);
        }
        return inString;
    }

    public Element getElementFromChildren(Element inHit, String inTagName) {
        for (Iterator<Element> iter2 = inHit.getChildren().iterator(); iter2.hasNext();) {
            Element myElement = iter2.next();
            String feldname = myElement.getAttributeValue("tag");
            // System.out.println(feldname);
            /*
             * wenn es das gesuchte Feld ist, dann den Wert mit dem passenden Attribut zurückgeben
             */
            if (feldname.equals(inTagName)) {
                return myElement;
            }
        }
        return null;
    }

    /**
     * rekursives Kopieren von Elementen, weil das Einfügen eines Elements an einen anderen Knoten mit dem Fehler abbricht, dass das einzufügende
     * Element bereits einen Parent hat ================================================================
     */
    public Element getCopyFromJdomElement(Element inHit) {
        Element myElement = new Element(inHit.getName());
        myElement.setText(inHit.getText());
        /* jetzt auch alle Attribute übernehmen */
        if (inHit.getAttributes() != null) {
            for (Iterator<Attribute> iter = inHit.getAttributes().iterator(); iter.hasNext();) {
                Attribute att = iter.next();
                myElement.getAttributes().add(new Attribute(att.getName(), att.getValue()));
            }
        }
        /* jetzt auch alle Children übernehmen */
        if (inHit.getChildren() != null) {

            for (Iterator<Element> iter = inHit.getChildren().iterator(); iter.hasNext();) {
                Element ele = iter.next();
                myElement.addContent(getCopyFromJdomElement(ele));
            }
        }
        return myElement;
    }

    public String getElementFieldValue(Element myFirstHit, String inFieldName, String inAttributeName) {

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

    public String getFieldValue(Element inElement, String attributeValue) {
        String rueckgabe = "";

        for (Iterator<Element> iter = inElement.getChildren().iterator(); iter.hasNext();) {
            Element subElement = iter.next();
            if (subElement.getAttributeValue("code").equals(attributeValue)) {
                rueckgabe = subElement.getValue();
            }
        }
        return rueckgabe;
    }

    /* (non-Javadoc)
     * @see de.sub.goobi.Import.IOpac#getAtstsl()
     */
    @Override
    public String getAtstsl() {
        return this.atstsl;
    }

    @Override
    public ConfigOpacDoctype getOpacDocType() {
        ConfigOpac co = null;
//      try {
//      co = new ConfigOpac();
//  } catch (IOException e) {
//  }
  co = ConfigOpac.getInstance();

        
        ConfigOpacDoctype cod = co.getDoctypeByMapping(this.gattung.substring(0, 2), this.coc.getTitle());
        if (cod == null) {
            if (verbose) {
                Helper.setFehlerMeldung(Helper.getTranslation("CatalogueUnKnownType") + ": ", this.gattung);
            }
            cod = co.getAllDoctypes().get(0);
            this.gattung = cod.getMappings().get(0);
            if (verbose) {
                Helper.setFehlerMeldung(Helper.getTranslation("CatalogueChangeDocType") + ": ", this.gattung + " - " + cod.getTitle());
            }
        }
        return cod;

    }

    @Override
    public PluginType getType() {
        return PluginType.Opac;
    }

    @Override
    public String getTitle() {
        return "HAABPICA";
    }

    public String getDescription() {
        return "HAABPICA";
    }

    public void setAtstsl(String createAtstsl) {
        atstsl = createAtstsl;
    }

    public String getGattung() {
        return gattung;
    }
}
