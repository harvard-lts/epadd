<%@page contentType="text/html; charset=UTF-8"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page language="java" import="com.google.gson.Gson"%>
<%@page language="java" import="edu.stanford.muse.email.AddressBook"%>
<%@page language="java" import="edu.stanford.muse.index.Archive"%>
<%@page language="java" import="java.util.ArrayList"%>
<%@page language="java" import="java.util.List"%>
<%@page language="java" import="java.util.Set"%>
<%@ page import="edu.stanford.muse.index.Document" %>
<%@ page import="edu.stanford.muse.index.EmailDocument" %>
<%@ page import="edu.stanford.muse.webapp.ModeConfig" %>
<%@page language="java" %>
<!DOCTYPE HTML>
<html>
<head>
	<meta name="viewport" content="width=device-width, initial-scale=1">
	<title>ePADD export</title>
	<link rel="icon" type="image/png" href="images/epadd-favicon.png">


	<link rel="stylesheet" href="bootstrap/dist/css/bootstrap.min.css">
	<link href="jqueryFileTree/jqueryFileTree.css" rel="stylesheet" type="text/css" media="screen" />
    <link href="css/selectpicker.css" rel="stylesheet" type="text/css" media="screen" />
	<jsp:include page="css/css.jsp"/>
  <!--
    <link rel="stylesheet" href="css/main.css">
-->
	<script src="js/jquery.js"></script>
	<script type="text/javascript" src="bootstrap/dist/js/bootstrap.min.js"></script>
	<script src="jqueryFileTree/jqueryFileTree.js"></script>
	<script src="js/filepicker.js"></script>
    <script src="js/selectpicker.js"></script>

	<script src="js/muse.js"></script>
	<script src="js/epadd.js"></script>
	<style>


		.mini-box { height: 105px; vertical-align:top;background-color: #f5f5f8; display: inline-block; width:200px; padding:20px; margin-right:22px;}
        .mini-box-icon { display: inline-block; width: 35px; vertical-align:top; color: #f2c22f; font-size: 175%;}
        .mini-box .number { font-size: 175%; margin-bottom:5px; }
        .mini-box-description { font-size: 14px; color: #999; display: inline-block; width: 100px; vertical-align:top; }
        .go-button, .go-button:hover { background-color: #0075bb; color: #fff; }  /* saumya wants this always filled in, not just on hover */
        .mini-box:hover, .mini-box-description:hover { background-color: #0075bb; color: #fff; cursor: pointer; }

        .btn-default { height: 37px; }
        .panel { background-color: #f5f5f8;  border: 1px solid #eaeaea;  padding:25px 30px;  border-radius: 4px;  margin-bottom: 25px; } /* taken from search-wraper in adv. search.scss */
        .panel-heading { font-size: 16px; font-weight: 400; /* weight 400 is equal to open sans regular */ margin-bottom:20px; color: #404040;} /* taken from h4/search-wrapper in adv. search.scss */
         label {  font-size: 14px; padding-bottom: 13px; font-weight: 400; color: #404040; } /* taken from form-group label in adv.search.scss */
        .faded { opacity: 0.5; }
        .one-line::after {  content:"";  display:block;  clear:both; }  /* clearfix needed, to take care of floats: http://stackoverflow.com/questions/211383/what-methods-of-clearfix-can-i-use */
        .picker-buttons { margin-top:40px; margin-left:-30px; }
        .form-group { margin-bottom: 25px;}

    </style>
</head>
<body style="background-color:white;">
<jsp:include page="header.jspf"/>
<jsp:include page="div_filepicker.jspf"/>

<script>epadd.nav_mark_active('Export');</script>

<%@include file="profile-block.jspf"%>

<%
Archive archive = (Archive) JSPHelper.getSessionAttribute(session, "archive");
String bestName = "";
String bestEmail = "";
if (archive != null) {
	AddressBook ab = archive.addressBook;
	Set<String> addrs = ab.getOwnAddrs();
	if (addrs.size() > 0)
		bestEmail = addrs.iterator().next();
	writeProfileBlock(out, archive, "", "Export archive");
}

    int messagesToExport = 0, annotatedMessages = 0, restrictedMessages = 0, messagesNotToExport = 0;
    for (Document d: archive.getAllDocs()) {
         EmailDocument ed = (EmailDocument) d;
         if (ed.doNotTransfer)
             messagesNotToExport++;
         if (ed.transferWithRestrictions)
             restrictedMessages++;
         if (!ed.doNotTransfer && !ed.transferWithRestrictions)
              messagesToExport++;
         if (!Util.nullOrEmpty(ed.comment))
             annotatedMessages++;
    }
%>

<p>

<div id="all_fields" style="margin-left:170px; width:900px; padding: 10px">
	<b>Review messages</b>
    <br/>
	<br/>
	<div onclick="window.location='export-review?type=transfer'" class="mini-box">
        <div class="mini-box-icon"><i class="fa fa-envelope-o"></i></div>
        <div class="mini-box-description">
            <div class="number"><%=Util.commatize(messagesToExport)%></div>
            Unrestricted messages
        </div>
    </div>

	<div onclick="window.location='export-review?type=annotated'" class="mini-box">
        <div class="mini-box-icon"><i class="fa fa-comment-o"></i></div>
        <div class="mini-box-description">
            <div class="number"><%=Util.commatize(annotatedMessages)%></div>
            Annotated messages
        </div>
	</div>

	<div onclick="window.location='export-review?type=transferWithRestrictions'" class="mini-box">
        <div class="mini-box-icon"><i class="fa fa-exclamation-triangle"></i></div>
        <div class="mini-box-description">
            <div class="number"><%=Util.commatize(restrictedMessages)%></div>
            Restricted messages
        </div>
	</div>

	<div onclick="window.location='export-review?type=doNotTransfer'" class="mini-box" style="margin-right:0px">
        <div class="mini-box-icon"><i class="fa fa-ban"></i></div>
        <div class="mini-box-description">
            <div class="number"><%=Util.commatize(messagesNotToExport)%></div>
            Messages not to export
        </div>
	</div>

	<br/>
	<br/>

	<section>
		<div class="panel">
			<div class="panel-heading">Export messages and attachments</div>

            <div class="one-line" id="export-next">
                <div class="form-group col-sm-8">
                    <label for="export-attach-file">Export to next ePADD module</label>
                    <input id="export-next-file" class="dir form-control" type="text" name="name" value=""/>
                </div>
                <div class="form-group col-sm-4 picker-buttons">
                    <button id="export-next-browse" class="btn-default browse-button">Browse</button>
                    <button id="export-next-do" style="margin-left: 10px;" class="go-button faded btn-default">Export</button>
                </div>
            </div>

            <div class="one-line" id="export-mbox">
                <div class="form-group col-sm-8" >
                    <label for="export-mbox-file">Export to mbox</label>
                    <input id="export-mbox-file" class="dir form-control" type="text" name="name" value=""/>
                </div>
                <div class="form-group col-sm-4 picker-buttons">
                    <button id="export-mbox-browse" class="btn-default browse-button">Browse</button>
                    <button id="export-mbox-do" style="margin-left: 10px;" class="go-button faded btn-default">Export</button>
                </div>
            </div>

		</div>
	</section>

    <section>
        <div class="panel">
            <div class="panel-heading">Export attachments</div>

            <div class="one-line">
                <div class="advanced-search form-group col-sm-6" style="padding:0px 0px 0px 15px">
                    <label for="attachmentType">Type</label>
                    <select name="attachmentType" id="attachmentType" class="form-control multi-select selectpicker" title="Select" multiple>
                        <option value="" selected disabled>Select</option>
                        <option value="jpg;png;gif;bmp">Graphics (jpg, png, gif, bmp)</option>
                        <option value="doc;docx;pages">Document (doc, docx, pages)</option>
                        <option value="ppt;pptx;key">Presentation (ppt, pptx, key)</option>
                        <option value="xls;xlsx;numbers">Spreadsheet (xls, xlsx, numbers)</option>
                        <option value="htm;html;css;js">Internet file (htm, html, css, js)</option>
                        <option value="zip;7z;tar;tgz">Compressed (zip, 7z, tar, tgz)</option>
                        <option value="mp3;ogg">Audio (mp3, ogg)</option>
                        <option value="avi;mp4">Video (avi, mp4)</option>
                        <option value="fmp;db;mdb;accdb">Database (fmp, db, mdb, accdb)</option>
                    </select>
                </div>

                <!--Extension-->
                <div class="form-group col-sm-6">
                    <label for="attachmentExtension">Other extension</label>
                    <input name="attachmentExtension" id="attachmentExtension" type="text" class="form-control">
                </div>

                <br/>

            </div>

            <div class="one-line" id="export-attach">
                <div class="form-group col-sm-8">
                    <label for="export-attach-file">Specify location</label>
                    <input id="export-attach-file" class="dir form-control" type="text" name="name" value=""/>
                </div>
                <div class="form-group col-sm-4 picker-buttons">
                    <button id="export-attach-browse" class="btn-default browse-button">Browse</button>
                    <button id="export-attach-do" style="margin-left: 10px;" class="go-button faded btn-default">Export</button>
                </div>
            </div>

            <br/>
        </div>
    </section>

    <section style="margin-bottom:100px">
        <div class="panel" id="export-auth">
            <div class="panel-heading">Export authorities (CSV)</div>

            <div class="one-line">
                <div class="form-group col-sm-8">
                    <label for="export-auth-file">Specify location</label>
                    <input id="export-auth-file" class="dir form-control" type="text" name="name" value=""/>
                </div>
                <div class="form-group col-sm-4 picker-buttons">
                    <button id="export-auth-browse" class="btn-default browse-button">Browse</button>
                    <button id="export-auth-do" style="margin-left: 10px;" class="go-button faded btn-default">Export</button>
                </div>
            </div>


            <br/>
        </div>
    </section>

</div> <!--  all fields -->

<p>

	<script type="text/javascript">
		$(document).ready(function() {
            new FilePicker($('#export-next'));
            new FilePicker($('#export-mbox'));
            new FilePicker($('#export-attach'));
            new FilePicker($('#export-auth'));
		});

        $('#export-next .go-button').click (function(e) {
            var $button = $(e.target);
            if ($button.hasClass('faded'))
                return false; // do nothing;
            var baseUrl = '<%=ModeConfig.isProcessingMode() ? "export-complete-processing":"export-complete"%>';
            var dir = $('.dir', $button.closest('.panel')).val();
            if (dir && dir.length > 0)
                window.location = baseUrl + '?dir=' + dir;
        });

        $('#export-mbox .go-button').click (function(e) {
            var $button = $(e.target);
            if ($button.hasClass('faded'))
                return false; // do nothing;
            var baseUrl = '<%=ModeConfig.isProcessingMode() ? "export-complete-processing":"export-complete"%>';
            var dir = $('.dir', $button.closest('.panel')).val();
            if (dir && dir.length > 0)
                window.location = baseUrl + '?dir=' + dir;
        });

	</script>

</body>
</html>
