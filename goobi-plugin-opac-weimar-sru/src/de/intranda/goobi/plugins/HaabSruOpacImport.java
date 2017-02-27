/**
 * This file is part of the SRU opac import plugin for the Goobi Application - a Workflow tool for the support of mass digitization.
 * 
 * Visit the websites for more information. 
 *          - http://digiverso.com 
 *          - http://www.intranda.com
 * 
 * Copyright 2013, intranda GmbH, Göttingen
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


import net.xeoh.plugins.base.annotations.PluginImplementation;

import java.io.IOException;

import org.apache.log4j.Logger;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IOpacPlugin;
import org.w3c.dom.Node;

import ugh.dl.Fileformat;
import ugh.dl.Prefs;
import de.intranda.goobi.plugins.sru.SRUHelper;
import de.sub.goobi.helper.UghHelper;
import de.unigoettingen.sub.search.opac.ConfigOpac;
import de.unigoettingen.sub.search.opac.ConfigOpacCatalogue;
import de.unigoettingen.sub.search.opac.ConfigOpacDoctype;

@PluginImplementation
public class HaabSruOpacImport implements IOpacPlugin {

    private int hitcount;
    private String gattung = "Aa";
    private String atstsl;
    private ConfigOpacCatalogue coc;

    @Override
    public Fileformat search(String inSuchfeld, String searchValue, ConfigOpacCatalogue cat, Prefs inPrefs) throws Exception {
        coc = cat;
        String searchField = "";
        String catalogue = cat.getAddress();
        if (inSuchfeld.equals("12")) {
            searchField = "pica.ppn";
        } else if (inSuchfeld.equals("8000")) {
            searchField = "pica.epn";
        } else if (inSuchfeld.equals("7")) {
            searchField = "pica.isb";
        } else if (inSuchfeld.equals("8")) {
            searchField = "pica.iss";
        }

        String value = SRUHelper.search(catalogue, searchField, searchValue);
        Node node = SRUHelper.parseResult(this, catalogue, value);
        Fileformat ff = SRUHelper.parsePicaFormat(this, node, inPrefs, searchValue);

        return ff;
    }

    /* (non-Javadoc)
     * @see de.sub.goobi.Import.IOpac#getHitcount()
     */
    @Override
    public int getHitcount() {
        return this.hitcount;
    }

    public void setHitcount(int hitcount) {
        this.hitcount = hitcount;
    }

    public void setGattung(String gattung) {
        this.gattung = gattung;
    }

    public String getGattung() {
        return gattung;
    }

    /* (non-Javadoc)
     * @see de.sub.goobi.Import.IOpac#getAtstsl()
     */
    @Override
    public String getAtstsl() {
        return this.atstsl;
    }

    public void setAtstsl(String atstsl) {
        this.atstsl = atstsl;
    }

    @Override
    public PluginType getType() {
        return PluginType.Opac;
    }

    @Override
    public String getTitle() {
        return "HAABSRU";
    }

    public String getDescription() {
        return "HAABSRU";
    }

    @Override
    public ConfigOpacDoctype getOpacDocType() {
        ConfigOpac co = null;
        try {
            co = new ConfigOpac();
        } catch (IOException e) {
        }
//        co = ConfigOpac.getInstance();
        ConfigOpacDoctype cod = co.getDoctypeByMapping(this.gattung.substring(0, 2), this.coc.getTitle());
        if (cod == null) {

            cod = co.getAllDoctypes().get(0);
            this.gattung = cod.getMappings().get(0);

        }
        return cod;
    }

    public String createAtstsl(String myTitle, String autor) {
        String titleValue = "";
        if (myTitle != null && !myTitle.isEmpty()) {
            if (myTitle.contains(" ")) {
                titleValue = myTitle.substring(0, myTitle.indexOf(" "));
            } else {
                titleValue = myTitle;
            }
        }

        if (titleValue.length() > 6) {
            atstsl = titleValue.substring(0, 6);
        } else {
            atstsl = titleValue;
        }
        atstsl = UghHelper.convertUmlaut(atstsl);
        atstsl = atstsl.replaceAll("[\\W]", "");
        return atstsl;
    }
}
