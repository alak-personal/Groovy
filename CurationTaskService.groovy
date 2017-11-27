package org.jax.ckb

import grails.transaction.Transactional
import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.text.SimpleTemplateEngine
import org.jax.cga.CurationTask
import org.jax.cga.GeneService

import static org.jax.ckb.JobLogService.JOB_LOG_STATUS_FAILED
import static org.jax.ckb.JobLogService.JOB_LOG_STATUS_SUCCESS

@Transactional
class CurationTaskService {

    public static final String GENE_VARIANT_DATA_ELEMENT = 'gene variant'
    public static final String EE_DATA_ELEMENT = 'efficacy evidence'

    public static final String TASK_TYPE_MAINTENANCE = 'maintenance'
    public static final String TASK_TYPE_MAINTENANCE_NON_ACTIONABLE_GV = 'maintenance_nonActionable_gv'

    Sql groovySql
    JobLogService jobLogService
    GeneService geneService

    public static final String ACTIONABLE_GENES_QUERY = '''
        (SELECT distinct gene.gene_id
         FROM profile_treatment_approach
            JOIN molecular_profile on molecular_profile.profile_id = profile_treatment_approach.profile_id
            JOIN molecular_profile_xref on molecular_profile_xref.profile_id = molecular_profile.profile_id
            JOIN gene_variant on gene_variant.variant_id = molecular_profile_xref.variant_id
            JOIN gene on gene.gene_id = gene_variant.gene_id
         WHERE gene_variant.variant_entry NOT IN ('over exp', 'wild-type', 'negative', 'positive', 'dec exp')
         GROUP BY gene.gene_id)
         UNION
        (SELECT distinct gene.gene_id
         FROM clinical_trial_profile
            JOIN clinical_trial on clinical_trial.nct_id = clinical_trial_profile.nct_id
            JOIN molecular_profile on molecular_profile.profile_id = clinical_trial_profile.profile_id
            JOIN molecular_profile_xref on molecular_profile_xref.profile_id = molecular_profile.profile_id
            JOIN gene_variant on gene_variant.variant_id = molecular_profile_xref.variant_id
            JOIN gene on gene.gene_id = gene_variant.gene_id
         WHERE gene_variant.variant_entry NOT IN ('over exp', 'wild-type', 'negative', 'positive', 'dec exp')
               AND clinical_trial.recruitment IN ('Recruiting', 'Not yet recruiting')
               AND clinical_trial_profile.requirement_type != 'excluded'
         GROUP BY gene.gene_id)
    '''

    public static final String MAINTENANCE_GENE_VARIANTS_FOR_ACTIONABLE_GENES_QUERY = '''
            SELECT DISTINCT gene_variant.variant
                , gene.gene_symbol
                , concat_ws(' ', gene.gene_symbol, gene_variant.variant) AS 'curationId'
                , null AS 'notes'
            FROM gene_variant
                JOIN gene ON gene.gene_id = gene_variant.gene_id
            WHERE gene_variant.protein_effect = 'unknown' AND
                  DATE(gene_variant.update_date) <= DATE(NOW() - INTERVAL 6 MONTH) AND
                  gene_variant.variant_entry NOT IN ('act mut','amp','dec exp','del','fusion','inact mut','loss','mutant','negative','over exp','positive','rearrange','wild-type') AND
                  gene.gene_id IN (${geneIdsPlaceholder})'''

    public static final String MAINTENANCE_EFFICACY_EVIDENCES_QUERY = '''
            SELECT response_efficacy_evidence.response_id
                , molecular_profile.profile_name
                , concat_ws(' ', molecular_profile.profile_name, CAST(response_efficacy_evidence.response_id AS CHAR)) AS 'curationId'
                , response_efficacy_evidence.efficacy_evidence AS 'notes'
            FROM efficacy_evidence_reference
                JOIN response_efficacy_evidence ON response_efficacy_evidence.annotation_id = efficacy_evidence_reference.annotation_id
                JOIN reference ON reference.reference_id = efficacy_evidence_reference.reference_id
                JOIN profile_response ON profile_response.response_id = response_efficacy_evidence.response_id
                JOIN molecular_profile ON molecular_profile.profile_id = profile_response.profile_id
            WHERE DATE(response_efficacy_evidence.update_date) <= DATE(NOW() - INTERVAL 3 MONTH) AND
                reference.url LIKE '%https://clinicaltrials.gov/%'
        '''

    void runMaintenanceCurationTasksCronJob() {
        runMaintenanceCurationTaskJob(GENE_VARIANT_DATA_ELEMENT)
        runMaintenanceCurationTaskJob(GENE_VARIANT_DATA_ELEMENT, TASK_TYPE_MAINTENANCE_NON_ACTIONABLE_GV)
        runMaintenanceCurationTaskJob(EE_DATA_ELEMENT)
    }

    private void runMaintenanceCurationTaskJob(String dataElementType, String taskType = TASK_TYPE_MAINTENANCE) {
        final Map curationTasksJobMap = runMaintenanceQueryForCurationTask(dataElementType, taskType)
        // create a jobLog and insert into job log table
        final Map jobLogMap = createJobLogMap(dataElementType, taskType, curationTasksJobMap)
        jobLogService.insertEntry(jobLogMap.name, jobLogMap.message, jobLogMap.status)
    }

    private Map runMaintenanceQueryForCurationTask(String dataElementType, String taskType = TASK_TYPE_MAINTENANCE) {
        final List<Map> maintenanceTaskCurationTaskMapsList = getMaintenanceTasksCurationTaskMapsList(dataElementType, taskType)
        final List<CurationTask> maintenanceCurationTasks = []
        String statusMessage
        boolean bCurationTaskSaveSuccess
        final List<String> savedCurationIds = []
        try {
            maintenanceCurationTasks = createMaintenanceCurationTasks(maintenanceTaskCurationTaskMapsList, dataElementType, taskType)
            bCurationTaskSaveSuccess = maintenanceCurationTasks.every { CurationTask ct -> ct.save(flush: true) }
            Map saveStatusMap = getSaveStatus(bCurationTaskSaveSuccess, maintenanceCurationTasks)
            savedCurationIds = saveStatusMap.savedCurationIds
            statusMessage = saveStatusMap.statusMessage
        } catch (Exception e) {
            bCurationTaskSaveSuccess = false
            statusMessage = "Error creating 1 or more curation tasks : " + e.getMessage()
        }

        [bCurationTaskSaveSuccess: bCurationTaskSaveSuccess, savedCurationIds: savedCurationIds, statusMessage: statusMessage]
    }

    private Map getSaveStatus(boolean bCurationTaskSaveSuccess, List<CurationTask> maintenanceCurationTasks) {
        final List<String> savedCurationIds = []
        String statusMessage
        if (bCurationTaskSaveSuccess) {
            savedCurationIds = maintenanceCurationTasks.collect { CurationTask ct -> ct.curationId }
        } else {
            statusMessage = "Error saving curation tasks."
        }
        [savedCurationIds: savedCurationIds, statusMessage: statusMessage]
    }

    private List<Map> getMaintenanceTasksCurationTaskMapsList(String dataElementType, String taskType = TASK_TYPE_MAINTENANCE) {
        // check for valid/allowed dataElement and taskType
        if ( !(dataElementType.equals(GENE_VARIANT_DATA_ELEMENT) && ( taskType.equals(TASK_TYPE_MAINTENANCE) || taskType.equals(TASK_TYPE_MAINTENANCE_NON_ACTIONABLE_GV) ) ) &&
             !(dataElementType.equals(EE_DATA_ELEMENT) && taskType.equals(TASK_TYPE_MAINTENANCE))
           ) {
            throw new RuntimeException("Invalid data element and/or task type for generating maintenane curation task")
        }
        // If dataElement and taskType are valid, construct the maintenace query and params if needed
        final Map maintenanceQueryMap = getMaintenanceQueryAndParams(dataElementType, taskType)
        final List<GroovyRowResult> rows = groovySql.rows(maintenanceQueryMap?.query, maintenanceQueryMap?.params as List)
        final List<Map> maintenanceTaskCurationTaskMapsList = rows.collect { row ->
            [curationId: row.curationId, notes: row.notes]
        }
        maintenanceTaskCurationTaskMapsList.unique()
    }

    private Map getMaintenanceQueryAndParams(String dataElementType, String taskType = TASK_TYPE_MAINTENANCE) {
        String maintenanceQuery
        List<Long> geneIdsList = []
        if (dataElementType.equals(GENE_VARIANT_DATA_ELEMENT)) {
            geneIdsList = getGeneIdsForGeneVariantTaskQuery(taskType)
            // query for maintenance gene variant
            maintenanceQuery = buildGeneVariantQueryWithGeneIds(geneIdsList)
        } else if (dataElementType.equals(EE_DATA_ELEMENT) && taskType.equals(TASK_TYPE_MAINTENANCE)) {
            maintenanceQuery = MAINTENANCE_EFFICACY_EVIDENCES_QUERY
        }

        return [query: maintenanceQuery, params: geneIdsList]
    }

    private List<Long> getGeneIdsForGeneVariantTaskQuery(String taskType) {
        List<Long> queryParamGeneIdsList = []
        // get actionable genes ids
        List<Long> actionableGenesIdsList = getActionableGeneIdsList()
        if (taskType.equals(TASK_TYPE_MAINTENANCE)) {
            queryParamGeneIdsList = actionableGenesIdsList
        } else if (taskType.equals(TASK_TYPE_MAINTENANCE_NON_ACTIONABLE_GV)) {
            // non-actionable geneIds list = (all genes with desc list - actionable genes list)
            List<Long> allGenesWithDescIdsList = geneService.findAllGenesWithDescriptionsAndReferences()*.id
            List<Long> nonActionableGeneIdsList = allGenesWithDescIdsList - actionableGenesIdsList
            queryParamGeneIdsList = nonActionableGeneIdsList
        }
        queryParamGeneIdsList
    }

    private List<Long> getActionableGeneIdsList() {
        final List<GroovyRowResult> rows = groovySql.rows(ACTIONABLE_GENES_QUERY)
        List<Long> actionableGenesIdsList = rows.collect { row -> row.gene_id }
        actionableGenesIdsList
    }

    private String buildGeneVariantQueryWithGeneIds(List<Long> genesIdsList) {
        String maintenanceQuery
        if (genesIdsList.size() > 0) {
            final String geneIdsPlaceholder = genesIdsList.collect { "?" }.join(",")
            final Map placeHoldersParamMap = [geneIdsPlaceholder: geneIdsPlaceholder]
            final SimpleTemplateEngine simpleTemplateEngine = new SimpleTemplateEngine()
            maintenanceQuery = simpleTemplateEngine.createTemplate(MAINTENANCE_GENE_VARIANTS_FOR_ACTIONABLE_GENES_QUERY).make(placeHoldersParamMap)
        }
        maintenanceQuery
    }

    private List<CurationTask> createMaintenanceCurationTasks(List<Map> maintenanceCurationTaskMapsList, String dataElementType, String taskTypeStr = TASK_TYPE_MAINTENANCE) {
        final List<CurationTask> curationTasksToAdd = []
        final String username = 'maintenanceCronJob'
        final Date createUpdateDate = new Date()
        for (Map curationTaskMap in maintenanceCurationTaskMapsList) {
            String curationIdStr = curationTaskMap.curationId
            String notes = curationTaskMap.notes
            List<CurationTask> curationTaskList = CurationTask.where {
                curationId == curationIdStr &&
                dataElement == dataElementType &&
                taskType == taskTypeStr &&
                curationStatus != 'check-in'
            }.list()
            if (curationTaskList.size() == 0) {
                CurationTask curationTask = CurationTask.createMaintenanceCurationTask(curationIdStr, dataElementType, taskTypeStr, notes, username, createUpdateDate)
                curationTasksToAdd.add(curationTask)
            }
        }
        curationTasksToAdd.unique()
    }

    private Map createJobLogMap(String dataElementType, String taskType = TASK_TYPE_MAINTENANCE, Map curationTasksJobMap) {
        final String jobLogName = constructJobLogName(dataElementType, taskType)
        final String jobLogMsg = constructJobLogMessage(curationTasksJobMap, dataElementType)
        final String jobLogStatus = curationTasksJobMap.bCurationTaskSaveSuccess? JOB_LOG_STATUS_SUCCESS : JOB_LOG_STATUS_FAILED
        [name: jobLogName, message: jobLogMsg, status: jobLogStatus]
    }

    private String constructJobLogMessage(Map curationTasksJobMap, String dataElementType) {
        String jobLogMsg
        if (curationTasksJobMap.bCurationTaskSaveSuccess) {
            List<String> curationIds = curationTasksJobMap.savedCurationIds
            jobLogMsg = "Successfully added " + curationIds.size() + " " + dataElementType + " maintenance curation tasks to curation workflow : " + curationIds
            // msg column in jobLog table in database is only 3500 chars long. Truncate jobLogMsg if it is longer than this limit
            if (jobLogMsg.length() > 3450) {
                jobLogMsg = jobLogMsg.substring(0, 3450) + " ... ]"
            }
        } else {
            jobLogMsg = "Error processing " + dataElementType + " maintenance tasks : " + curationTasksJobMap.statusMessage
        }
        jobLogMsg
    }

    private String constructJobLogName(String dataElementType, String taskType) {
        final String jobLogName
        if (dataElementType.equals(GENE_VARIANT_DATA_ELEMENT)) {
            jobLogName = taskType.equals(TASK_TYPE_MAINTENANCE) ? 'Actionable Gene Variants Maintenance' : 'Non-actionable Gene Variants Maintenance'
        } else if (dataElementType.equals(EE_DATA_ELEMENT) && taskType.equals(TASK_TYPE_MAINTENANCE)) {
            jobLogName = 'Efficacy Evidence Maintenance'
        }
        jobLogName
    }

}
