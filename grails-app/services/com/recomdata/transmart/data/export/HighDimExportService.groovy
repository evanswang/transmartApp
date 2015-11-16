package com.recomdata.transmart.data.export

import org.transmart.db.dataquery.mrna.AnnotationRecord
import org.transmartproject.core.dataquery.DataRow
import org.transmartproject.core.dataquery.TabularResult
import org.transmartproject.core.dataquery.highdim.AssayColumn
import org.transmartproject.core.dataquery.highdim.HighDimensionDataTypeResource
import org.transmartproject.core.dataquery.highdim.assayconstraints.AssayConstraint
import org.transmartproject.core.dataquery.highdim.projections.Projection
import org.transmartproject.core.ontology.OntologyTerm
import org.transmartproject.export.HighDimExporter

import org.codehaus.groovy.grails.commons.ConfigurationHolder
import org.transmart.db.dataquery.mrna.ExpressionRecord
import org.transmart.db.dataquery.vcf.KVVcfModule
import org.transmartproject.export.HighDimExporter
import org.transmart.db.dataquery.SQLModule
import org.transmart.db.dataquery.mrna.KVMrnaModule

class HighDimExportService {

    def highDimensionResourceService
    def highDimExporterRegistry
    def queriesResourceService
    def conceptsResourceService

    // FIXME: jobResultsService lives in Rmodules, so this is probably not a dependency we should have here
    def jobResultsService

    /**
     * - args.conceptPaths (optional) - collection with concept keys (\\<tableCode>\<conceptFullName>) denoting data
     * nodes for which to export data for.
     * - args.resultInstanceId        - id of patient set for denoting patients for which export data for.
     * - args.studyDir (File)         - directory where to store exported files
     * - args.format                  - data file format (e.g. "TSV", "VCF"; see HighDimExporter.getFormat())
     * - args.dataType                - data format (e.g. "mrna", "acgh"; see HighDimensionDataTypeModule.getName())
     * - args.jobName                 - name of the current export job to check status whether we need to break export.
     */
    def exportHighDimData(Map args) {

        Long resultInstanceId = args.resultInstanceId as Long
        List<String> conceptKeys = args.conceptKeys

        /*******************************
         * get annotation
        select
        smap.SUBJECT_ID, smap.SAMPLE_TYPE, smap.TIMEPOINT,
        smap.TISSUE_TYPE, smap.GPL_ID, smap.ASSAY_ID,
        smap.SAMPLE_CD, smap.TRIAL_NAME, smap.concept_code
        from i2b2demodata.qt_patient_set_collection pset
        inner join deapp.de_subject_sample_mapping smap
        on pset.patient_num = smap.patient_id
        where pset.result_instance_id = 28702;
        */

        if (jobIsCancelled(args.jobName)) {
            return null
        }

        def fileNames = []

        HighDimensionDataTypeResource dataTypeResource = highDimensionResourceService.getSubResourceForType(args.dataType)
        if (!conceptKeys) {
            def queryResult = queriesResourceService.getQueryResultFromId(resultInstanceId)
            def ontologyTerms = dataTypeResource.getAllOntologyTermsForDataTypeBy(queryResult)
            conceptKeys = ontologyTerms.collect { it.key }
        }
        conceptKeys.eachWithIndex { String conceptPath, int index ->
            // Add constraints to filter the output
            def file = exportForSingleNode(
                    conceptPath,
                    resultInstanceId,
                    args.studyDir,
                    args.format,
                    args.dataType,
                    index,
                    args.jobName)

            if (file) {
                fileNames << file.absolutePath
            }
        }

        return fileNames
    }

    private File exportForSingleNode(String conceptPath, Long resultInstanceId, File studyDir, String format, String dataType, Integer index, String jobName) {

        HighDimensionDataTypeResource dataTypeResource = highDimensionResourceService.getSubResourceForType(dataType)

        def assayConstraints = []

        assayConstraints << dataTypeResource.createAssayConstraint(
                AssayConstraint.PATIENT_SET_CONSTRAINT,
                result_instance_id: resultInstanceId)

        assayConstraints << dataTypeResource.createAssayConstraint(
                AssayConstraint.ONTOLOGY_TERM_CONSTRAINT,
                concept_key: conceptPath)

        // Setup class to export the data
        HighDimExporter exporter = highDimExporterRegistry.getExporterForFormat(format)
        Projection projection = dataTypeResource.createProjection(exporter.projection)

        // Retrieve the data itself

        // Start exporting
        // @wsc graft key value on to the relational function here for mrna and vcf format
        // check the mrna output format, write down different types of data,
        //File outputFile = new File(studyDir, dataType + '.' + format.toLowerCase())
        //String fileName = outputFile.getAbsolutePath()

        File outputFile = new File(studyDir,
                "${dataType}_${makeFileNameFromConceptPath(conceptPath)}_${index}.${format.toLowerCase()}")

        if(ConfigurationHolder.config.org.transmart.kv.enable && dataType.equals("mrna")) {
            // get patient ids
            List<BigDecimal> patientList = SQLModule.getPatients(resultInstanceId + "")
            // get trial_name and concept_cd from concept path
            Map<String, String> study_concecpt = SQLModule.getTrialandConceptCD(conceptPath.substring(ordinalIndexOf(conceptPath, '\\', 3)))
            System.err.println("************************************* wsc print concept path ************** " + conceptPath.substring(ordinalIndexOf(conceptPath, '\\', 3)));
            String studyName = study_concecpt.get("study_name")
            String conceptCD = study_concecpt.get("concept_cd")
            //
            Map<String, AnnotationRecord> patientMap = SQLModule.getPatientMapping(patientList, conceptCD)
            final String COL_FAMILY_RAW = "raw"
            final String COL_FAMILY_LOG = "log"
            final String COL_FAMILY_ZSCORE = "zscore"

            // default expression data type is raw, but you can use getAllRecords to get all raw, log and zscore
            KVMrnaModule kvMrnaModule = new KVMrnaModule("microarray", "raw")
            PrintWriter pw = null
            long t1 = System.currentTimeMillis()
            int count = 0
            try {

                //outputFiles << outputFile

                pw = new PrintWriter(outputFile)
                pw.println("PATIENT ID\tSAMPLE TYPE\tTIMEPOINT\tTISSUE TYPE\tGPL ID\tASSAY ID\tSAMPLE CODE\tTRIALNAME\tVALUE\tLOG2E\tZSCORE\tPROBE\tGENE ID\tGENE SYMBO")
                Map<String, List<ExpressionRecord>> resultsMap = new HashMap<String, List<ExpressionRecord>>()
                int i = 0
                patientMap.keySet().each { patientID ->
                    count ++;
                    resultsMap.put(patientID, kvMrnaModule.getAllRecords(studyName, patientID, conceptCD))
                    i++
                    if (i >= 20) {
                        resultsMap.keySet().each { paID ->
                            AnnotationRecord annoRecord = patientMap.get(paID)
                            resultsMap.get(paID).each { record ->
                                pw.println(annoRecord.getSubjectID() + "\t" +
                                        annoRecord.getSampleType() + "\t" +
                                        annoRecord.getTimePoint() + "\t" +
                                        annoRecord.getTissueType() + "\t" +
                                        annoRecord.getGplID() + "\t" +
                                        annoRecord.getAssayID() + "\t" +
                                        annoRecord.getSampleCD() + "\t" +
                                        studyName + "\t" +
                                        record.getValues().get(COL_FAMILY_RAW) + "\t" +
                                        record.getValues().get(COL_FAMILY_LOG) + "\t" +
                                        record.getValues().get(COL_FAMILY_ZSCORE) + "\t" +
                                        record.getProbeset() + "\t" +
                                        record.getGene() + "\t" +
                                        "null")
                            }
                        }
                        resultsMap.clear()
                        i = 0
                    }
                }
            } catch (IOException e) {
                e.printStackTrace()
            } finally {
                pw.close()
                long t2 = System.currentTimeMillis()
            }
        } else if (ConfigurationHolder.config.org.transmart.kv.enable && dataType.equals("vcf")) {
            List<BigDecimal> patientList = SQLModule.getPatients(resultInstanceId)
            Map<BigDecimal, String> patientMap = SQLModule.getPatientMap(patientList)
            PrintWriter pw = null
            KVVcfModule kvVcfModule = new KVVcfModule("vcf-subject.index");
            try {
                pw = new PrintWriter(outputFile)
                pw.println("TRIAL NAME\tPATIENT ID\tCHROMOSOME\tPOSITION\tASSAYID\tGPLID\tREFERENCE\tREFERENCEALLELE\tRSID\tSAMPLECODE\tSAMPLETYPE\tTIMEPOINT\tTISSUETYPE\tVARIANT\tVARIANTTYPE")
                patientMap.keySet().each { patientID ->
                    kvVcfModule.writeAllRecords(pw, patientMap.get(patientID), patientID)
                }
            } catch (IOException e) {
                e.printStackTrace()
            } finally {
                pw.close()
            }
        } else {
            TabularResult<AssayColumn, DataRow<Map<String, String>>> tabularResult =
                    dataTypeResource.retrieveData(assayConstraints, [], projection)

            try {
                outputFile.withOutputStream { outputStream ->
                    exporter.export tabularResult, projection, outputStream, { jobIsCancelled(jobName) }
                }
            } catch (RuntimeException e) {
                log.error('Data export to the file has thrown an exception', e)
            } finally {
                tabularResult.close()
            }
        }
        outputFile
    }

    private String makeFileNameFromConceptPath(String conceptPath) {
        conceptPath
                .split('\\\\')
                .reverse()[0..1]
                .join('_')
                .replaceAll('[\\W_]+', '_')
    }

    def boolean jobIsCancelled(jobName) {
        if (jobResultsService[jobName]["Status"] == "Cancelled") {
            log.warn("${jobName} has been cancelled")
            return true
        }
        return false
    }

   def int ordinalIndexOf(String str, char c, int n) {
        int pos = str.indexOf(c, 0);
        while (n-- > 0 && pos != -1)
            pos = str.indexOf(c, pos+1);
        return pos;
    }
}
