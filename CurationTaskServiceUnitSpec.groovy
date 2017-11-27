package org.jax.ckb

import grails.buildtestdata.mixin.Build
import grails.test.mixin.Mock
import grails.test.mixin.TestFor
import groovy.sql.Sql
import org.jax.cga.CurationTask
import org.jax.cga.Gene
import org.jax.cga.GeneService
import spock.lang.Specification
import spock.lang.Unroll

import static org.jax.ckb.CurationTaskService.EE_DATA_ELEMENT
import static org.jax.ckb.CurationTaskService.GENE_VARIANT_DATA_ELEMENT
import static org.jax.ckb.CurationTaskService.MAINTENANCE_EFFICACY_EVIDENCES_QUERY
import static org.jax.ckb.CurationTaskService.TASK_TYPE_MAINTENANCE_NON_ACTIONABLE_GV
import static org.jax.ckb.CurationTaskService.TASK_TYPE_MAINTENANCE
import static org.jax.ckb.JobLogService.JOB_LOG_STATUS_FAILED
import static org.jax.ckb.JobLogService.JOB_LOG_STATUS_SUCCESS

/**
 * See the API for {@link grails.test.mixin.services.ServiceUnitTestMixin} for usage instructions
 */
@TestFor(CurationTaskService)
@Build([CurationTask, Gene])
@Mock([CurationTask, Gene])
@Unroll
class CurationTaskServiceUnitSpec extends Specification {

    Sql groovySql
    GeneService geneService

    def setup() {
    }

    def cleanup() {
    }

    List<Gene> createGenesWithDescAndRef() {
        final List<Long> geneIds = [25, 27, 9212, 6795, 91]     // gene ids - some actionable, some non-actionable
        final List<Gene> genesWithDescRefList = []
        geneIds.each { i ->
            Gene gene = new Gene()
            gene.@id = i
            genesWithDescRefList.add(gene)
        }
        genesWithDescRefList

    }

     void createDbMocks() {
        groovySql = Mock()
        service.groovySql = groovySql
        geneService = Mock()
        service.geneService = geneService
    }

    private static String MAINTENANCE_GENEVAR_QUERY_TEMPLATE = '''
            SELECT DISTINCT gene_variant.variant
                , gene.gene_symbol
                , concat_ws(' ', gene.gene_symbol, gene_variant.variant) AS 'curationId'
                , null AS 'notes'
            FROM gene_variant
                JOIN gene ON gene.gene_id = gene_variant.gene_id
            WHERE gene_variant.protein_effect = 'unknown' AND
                  DATE(gene_variant.update_date) <= DATE(NOW() - INTERVAL 6 MONTH) AND
                  gene_variant.variant_entry NOT IN ('act mut','amp','dec exp','del','fusion','inact mut','loss','mutant','negative','over exp','positive','rearrange','wild-type') AND
                  gene.gene_id IN '''

    void "test getActionableGeneIdsList #desc"() {
        given:
        createDbMocks()

        when:
        List<Long> actualActionableGeneIds = service.getActionableGeneIdsList()

        then:
        1 * groovySql.rows(service.ACTIONABLE_GENES_QUERY) >> dbRows
        actualActionableGeneIds == expectedActionableGeneIds

        where:
        desc           | dbRows                         | expectedActionableGeneIds
        "no geneIds"   | []                             | []
        "with geneIds" | [[gene_id: 25], [gene_id: 27]] | [25, 27]
    }

    void "test buildGeneVariantQueryWithGeneIds #desc"() {

        when:
        String actualGeneVariantQuery = service.buildGeneVariantQueryWithGeneIds(geneIdsList)

        then:
        actualGeneVariantQuery == expectedGeneVariantQuery

        where:
        desc           | geneIdsList | expectedGeneVariantQuery
        "no geneIds"   | []          | null
        "with geneIds" | [25, 27]    | MAINTENANCE_GENEVAR_QUERY_TEMPLATE + "(?,?)"
    }

    void "test getGeneIdsForGeneVariantTaskQuery #desc"() {

        given:
        createDbMocks()
        List<Long> genesWithDescAndRefList = createGenesWithDescAndRef()

        when:
        List actualGeneIdsList = service.getGeneIdsForGeneVariantTaskQuery(taskType)

        then:
        1 * groovySql.rows(service.ACTIONABLE_GENES_QUERY) >> actionableGenesDbRows
        nonActInvocationCount * geneService.findAllGenesWithDescriptionsAndReferences() >> genesWithDescAndRefList
        actualGeneIdsList == expectedGeneIdsList

        where:
        desc                                                | taskType                                | nonActInvocationCount | actionableGenesDbRows          | expectedGeneIdsList
        "null taskType"                                     | null                                    | 0                     | [[gene_id: 25], [gene_id: 27]] | []
        "blank taskType"                                    | ""                                      | 0                     | [[gene_id: 25], [gene_id: 27]] | []
        "any string taskType"                               | "Any other String"                      | 0                     | [[gene_id: 25], [gene_id: 27]] | []

        "maintenance taskType; no actionable geneIds"       | TASK_TYPE_MAINTENANCE                   | 0                     | []                             | []
        "maintenance taskType; with actionable geneIds"     | TASK_TYPE_MAINTENANCE                   | 0                     | [[gene_id: 25], [gene_id: 27]] | [25, 27]
        "maintenance taskType; no actionable geneIds"       | TASK_TYPE_MAINTENANCE_NON_ACTIONABLE_GV | 1                     | []                             | [25, 27, 9212, 6795, 91]
        "maintenance taskType; with non-actionable geneIds" | TASK_TYPE_MAINTENANCE_NON_ACTIONABLE_GV | 1                     | [[gene_id: 25], [gene_id: 27]] | [9212, 6795, 91]
    }

    void "test getMaintenanceQueryAndParams #desc"() {

        given:
        createDbMocks()
        List<Long> genesWithDescAndRefList = createGenesWithDescAndRef()

        when:
        Map actualQueryMap = service.getMaintenanceQueryAndParams(dataElement, taskType)

        then:
        actInvocationCount * groovySql.rows(service.ACTIONABLE_GENES_QUERY) >> actionableGenesDbRows
        nonActInvocationCount * geneService.findAllGenesWithDescriptionsAndReferences() >> genesWithDescAndRefList
        actualQueryMap == expectedQueryMap

        where:
        desc                                                      | dataElement               | taskType                                | actInvocationCount | nonActInvocationCount | actionableGenesDbRows          | expectedQueryMap
        "null dataElement"                                        | null                      | null                                    | 0                  | 0                     | [[gene_id: 25], [gene_id: 27]] | [query: null, params: []]
        "blank dataElement"                                       | ""                        | ""                                      | 0                  | 0                     | [[gene_id: 25], [gene_id: 27]] | [query: null, params: []]
        "any string dataElement; with geneIds"                    | "Any String"              | "Any other String"                      | 0                  | 0                     | [[gene_id: 25], [gene_id: 27]] | [query: null, params: []]

        "(actionable) gene variant dataElement; no geneIds"       | GENE_VARIANT_DATA_ELEMENT | TASK_TYPE_MAINTENANCE                   | 1                  | 0                     | []                             | [query: null, params: []]
        "(actionable) gene variant dataElement; with geneIds"     | GENE_VARIANT_DATA_ELEMENT | TASK_TYPE_MAINTENANCE                   | 1                  | 0                     | [[gene_id: 25], [gene_id: 27]] | [query: MAINTENANCE_GENEVAR_QUERY_TEMPLATE + "(?,?)", params: [25, 27]]

        "(non-actionable) gene variant dataElement; no geneIds"   | GENE_VARIANT_DATA_ELEMENT | TASK_TYPE_MAINTENANCE_NON_ACTIONABLE_GV | 1                  | 1                     | []                             | [query: MAINTENANCE_GENEVAR_QUERY_TEMPLATE + "(?,?,?,?,?)", params: [25, 27, 9212, 6795, 91]]
        "(non-actionable) gene variant dataElement; with geneIds" | GENE_VARIANT_DATA_ELEMENT | TASK_TYPE_MAINTENANCE_NON_ACTIONABLE_GV | 1                  | 1                     | [[gene_id: 25], [gene_id: 27]] | [query: MAINTENANCE_GENEVAR_QUERY_TEMPLATE + "(?,?,?)", params: [9212, 6795, 91]]

        "efficacy evidence dataElement; with geneIds"             | EE_DATA_ELEMENT           | TASK_TYPE_MAINTENANCE                   | 0                  | 0                     | [[gene_id: 25], [gene_id: 27]] | [query: MAINTENANCE_EFFICACY_EVIDENCES_QUERY, params: []]
        "efficacy evidence dataElement; invalid taskType"         | EE_DATA_ELEMENT           | TASK_TYPE_MAINTENANCE_NON_ACTIONABLE_GV | 0                  | 0                     | [[gene_id: 25], [gene_id: 27]] | [query: null, params: []]

    }


    private static
    final Map GENE_VAR_MAP_V600E = [variant: 'V600E', gene_symbol: 'BRAF', curationId: 'BRAF V600E', notes: null]
    private static
    final Map GENE_VAR_MAP_G12S = [variant: 'G12S', gene_symbol: 'HRAS', curationId: 'HRAS G12S', notes: null]

    private static
    final Map GENE_VAR_MAP_R333Q = [variant: 'R333Q', gene_symbol: 'AURKB', curationId: 'AURKB R333Q', notes: null]
    private static
    final Map GENE_VAR_MAP_P269L = [variant: 'P269L', gene_symbol: 'AURKC', curationId: 'AURKC P269L', notes: null]

    private static
    final Map EE_MAP_V600E = [response_id: '123', profile_name: 'BRAF V600E', curationId: 'BRAF V600E 123', notes: "Some EE Text for V600E"]
    private static
    final Map EE_MAP_G12S = [response_id: '321', profile_name: 'HRAS G12S', curationId: 'HRAS G12S 321', notes: "Some EE Text for G12S"]

    void "test getMaintenanceTasksCurationTaskMapsList valid inputs #desc"() {

        given:
        createDbMocks()
        List<Long> genesWithDescAndRefList = createGenesWithDescAndRef()

        when:
        def actual = service.getMaintenanceTasksCurationTaskMapsList(dataElement, taskType)

        then:
        1 * groovySql.rows(_,_) >> dbRows
        nonActInvocationCount * geneService.findAllGenesWithDescriptionsAndReferences() >> genesWithDescAndRefList

        actual.toString() == expected.toString()


        where:
        desc                                           | dataElement               | taskType                                | nonActInvocationCount | dbRows                                   | expected
        "actionable gene variant (1) dataElement"      | GENE_VARIANT_DATA_ELEMENT | TASK_TYPE_MAINTENANCE                   | 0                     | [GENE_VAR_MAP_V600E]                     | [[curationId: 'BRAF V600E', notes: null]]
        "actionable gene variants (2) dataElement"     | GENE_VARIANT_DATA_ELEMENT | TASK_TYPE_MAINTENANCE                   | 0                     | [GENE_VAR_MAP_V600E, GENE_VAR_MAP_G12S]  | [[curationId: 'BRAF V600E', notes: null], [curationId: 'HRAS G12S', notes: null]]

        "non-actionable gene variant (1) dataElement"  | GENE_VARIANT_DATA_ELEMENT | TASK_TYPE_MAINTENANCE_NON_ACTIONABLE_GV | 1                     | [GENE_VAR_MAP_R333Q]                     | [[curationId: 'AURKB R333Q', notes: null]]
        "non-actionable gene variants (2) dataElement" | GENE_VARIANT_DATA_ELEMENT | TASK_TYPE_MAINTENANCE_NON_ACTIONABLE_GV | 1                     | [GENE_VAR_MAP_R333Q, GENE_VAR_MAP_P269L] | [[curationId: 'AURKB R333Q', notes: null], [curationId: 'AURKC P269L', notes: null]]

        "EEs (2) dataElement, maintenance taskType"    | EE_DATA_ELEMENT           | TASK_TYPE_MAINTENANCE                   | 0                     | [EE_MAP_V600E, EE_MAP_G12S]              | [[curationId: 'BRAF V600E 123', notes: "Some EE Text for V600E"], [curationId: 'HRAS G12S 321', notes: "Some EE Text for G12S"]]
    }

    void "test getMaintenanceTasksCurationTaskMapsList invalid inputs #desc"() {

        when:
        def actual = service.getMaintenanceTasksCurationTaskMapsList(dataElement, taskType)

        then:
        final RuntimeException rte = thrown()
        rte.message == expectedRTEMsg

        where:
        desc                                | dataElement     | taskType                                | expectedRTEMsg
        "null dataElement, taskType"        | null            | null                                    | 'Invalid data element and/or task type for generating maintenane curation task'
        "blank dataElement, taskType"       | ""              | ""                                      | 'Invalid data element and/or task type for generating maintenane curation task'
        "any dataElement, taskType"         | "any string"    | "any string"                            | 'Invalid data element and/or task type for generating maintenane curation task'

        "EEs dataElement, invalid taskType" | EE_DATA_ELEMENT | TASK_TYPE_MAINTENANCE_NON_ACTIONABLE_GV | 'Invalid data element and/or task type for generating maintenane curation task'
    }

    public static
    final Map GENE_VAR_MAP_WITH_ERROR = [variant: 'V600E', gene_symbol: 'BRAF', curationId: '', notes: null]
    public static
    final Map EE_MAP_WITH_ERROR = [response_id: '321', profile_name: 'HRAS G12S', curationId: '', notes: "Some Text for G12S"]

    void "test runMaintenanceQueryForCurationTask invalid input #desc"() {

        when:
        def actualMap = service.runMaintenanceQueryForCurationTask(dataElement, taskType)

        then:
        final RuntimeException rte = thrown()
        rte.message == expectedRTEMsg

        where:
        desc                                            | dataElement     | taskType                                | expectedRTEMsg
        "null dataElement"                              | null            | null                                    | 'Invalid data element and/or task type for generating maintenane curation task'
        "blank dataElement"                             | ""              | ""                                      | 'Invalid data element and/or task type for generating maintenane curation task'
        "any dataElement"                               | "any string"    | "any string"                            | 'Invalid data element and/or task type for generating maintenane curation task'

        "EEs (2) dataElement failure, invalid taskType" | EE_DATA_ELEMENT | TASK_TYPE_MAINTENANCE_NON_ACTIONABLE_GV | 'Invalid data element and/or task type for generating maintenane curation task'

    }

    void "test runMaintenanceQueryForCurationTask valid input #desc"() {

        given:
        createDbMocks()
        List<Long> genesWithDescAndRefList = createGenesWithDescAndRef()

        when:
        def actualMap = service.runMaintenanceQueryForCurationTask(dataElement, taskType)

        then:
        1 * groovySql.rows(_,_) >> dbRows
        nonActInvocationCount * geneService.findAllGenesWithDescriptionsAndReferences() >> genesWithDescAndRefList
        actualMap == expectedMap

        where:
        desc                                            | dataElement               | taskType                                | dbRows                                       | nonActInvocationCount | expectedMap
        "actionable gene variants (2) dataElement"      | GENE_VARIANT_DATA_ELEMENT | TASK_TYPE_MAINTENANCE                   | [GENE_VAR_MAP_V600E, GENE_VAR_MAP_G12S]      | 0                     | [bCurationTaskSaveSuccess: true, savedCurationIds: ['BRAF V600E', 'HRAS G12S'], statusMessage: null]

        "non-actionable gene variants (2) dataElement"  | GENE_VARIANT_DATA_ELEMENT | TASK_TYPE_MAINTENANCE_NON_ACTIONABLE_GV | [GENE_VAR_MAP_R333Q, GENE_VAR_MAP_P269L]     | 1                     | [bCurationTaskSaveSuccess: true, savedCurationIds: ['AURKB R333Q', 'AURKC P269L'], statusMessage: null]

        "EEs (2) dataElement"                           | EE_DATA_ELEMENT           | TASK_TYPE_MAINTENANCE                   | [EE_MAP_V600E, EE_MAP_G12S]                  | 0                     | [bCurationTaskSaveSuccess: true, savedCurationIds: ['BRAF V600E 123', 'HRAS G12S 321'], statusMessage: null]

        "gene variants (2) dataElement failure"         | GENE_VARIANT_DATA_ELEMENT | TASK_TYPE_MAINTENANCE                   | [GENE_VAR_MAP_WITH_ERROR, GENE_VAR_MAP_G12S] | 0                     | [bCurationTaskSaveSuccess: false, savedCurationIds: [], statusMessage: "Error creating 1 or more curation tasks : Cannot create maintenance task for null/empty curationId or createBy field."]
        "EEs (2) dataElement failure"                   | EE_DATA_ELEMENT           | TASK_TYPE_MAINTENANCE                   | [EE_MAP_V600E, EE_MAP_WITH_ERROR]            | 0                     | [bCurationTaskSaveSuccess: false, savedCurationIds: [], statusMessage: "Error creating 1 or more curation tasks : Cannot create maintenance task for null/empty curationId or createBy field."]

    }

    void "test getSaveStatus #desc"() {
        given:
        List<CurationTask> curationTasks = buildCurationTasks(curationIds)
        when:
        def actualMap = service.getSaveStatus(bCurationTaskSave, curationTasks)

        then:
        actualMap == expectedMap

        where:
        desc                               | bCurationTaskSave | curationIds                 | expectedMap
        "null dataElement"                 | true              | []                          | [savedCurationIds: [], statusMessage: null]
        "blank dataElement"                | false             | []                          | [savedCurationIds: [], statusMessage: "Error saving curation tasks."]

        "1 curation task - save success"   | true              | ['BRAF V600E']              | [savedCurationIds: ['BRAF V600E'], statusMessage: null]
        "2 curation tasks - save success " | true              | ['BRAF V600E', 'HRAS G12S'] | [savedCurationIds: ['BRAF V600E', 'HRAS G12S'], statusMessage: null]

        "1 curation task - save failure"   | false             | ['BRAF V600E']              | [savedCurationIds: [], statusMessage: "Error saving curation tasks."]
        "2 curation tasks - save failure " | false             | ['BRAF V600E', 'HRAS G12S'] | [savedCurationIds: [], statusMessage: "Error saving curation tasks."]
    }

    List<CurationTask> buildCurationTasks(List<String> curationIdsList) {
        final List<CurationTask> curationTasksList = []
        curationIdsList.each { String curationId ->
            CurationTask ct = CurationTask.build(curationId: curationId, dataElement: GENE_VARIANT_DATA_ELEMENT, taskType: "maintenance")
            curationTasksList.add(ct)
        }
        return curationTasksList
    }

    void "test constructJobLogName #desc"() {
        when:
        String actualJobLogName = service.constructJobLogName(dataElement, taskType)

        then:
        actualJobLogName == expectedJobLogName

        where:
        dataElement               | taskType                                | expectedJobLogName                         | desc
        GENE_VARIANT_DATA_ELEMENT | TASK_TYPE_MAINTENANCE                   | 'Actionable Gene Variants Maintenance'     | "gene variant data element, actionable maintenance task type"
        GENE_VARIANT_DATA_ELEMENT | TASK_TYPE_MAINTENANCE_NON_ACTIONABLE_GV | 'Non-actionable Gene Variants Maintenance' | "gene variant data element, non-actionable maintenance task type"
        EE_DATA_ELEMENT           | TASK_TYPE_MAINTENANCE                   | 'Efficacy Evidence Maintenance'            | "efficacy evidence data element, maintenance task type"
        EE_DATA_ELEMENT           | TASK_TYPE_MAINTENANCE_NON_ACTIONABLE_GV | null                                       | "EE data element, non-actionable maintenance task type - invalid"
        'PMID'                    | TASK_TYPE_MAINTENANCE                   | null                                       | "PMID data element, invalid input"

    }

    void "test constructJobLogMessage #desc"() {
        when:
        String actualJobLogMessage = service.constructJobLogMessage(curationTasksMap, dataElement)

        then:
        actualJobLogMessage == expectedJobLogMessage

        where:
        dataElement               | curationTasksMap                                                                                             | expectedJobLogMessage                                                                                                              | desc
        GENE_VARIANT_DATA_ELEMENT | [bCurationTaskSaveSuccess: true, savedCurationIds: ['BRAF V600E'], statusMessage: null]                      | 'Successfully added 1 ' + GENE_VARIANT_DATA_ELEMENT + ' maintenance curation tasks to curation workflow : [BRAF V600E]'            | "successfully saved (actionable) gene variant curationTask"
        GENE_VARIANT_DATA_ELEMENT | [bCurationTaskSaveSuccess: true, savedCurationIds: ['BRAF V600E', 'HRAS G12S'], statusMessage: null]         | 'Successfully added 2 ' + GENE_VARIANT_DATA_ELEMENT + ' maintenance curation tasks to curation workflow : [BRAF V600E, HRAS G12S]' | "successfully saved (actionable) gene variant curationTasks (2)"

        EE_DATA_ELEMENT           | [bCurationTaskSaveSuccess: true, savedCurationIds: ['BRAF V600E 123'], statusMessage: null]                  | 'Successfully added 1 ' + EE_DATA_ELEMENT + ' maintenance curation tasks to curation workflow : [BRAF V600E 123]'                  | "successfully saved EE curationTask"
        EE_DATA_ELEMENT           | [bCurationTaskSaveSuccess: true, savedCurationIds: ['BRAF V600E 123', 'HRAS G12S 321'], statusMessage: null] | 'Successfully added 2 ' + EE_DATA_ELEMENT + ' maintenance curation tasks to curation workflow : [BRAF V600E 123, HRAS G12S 321]'   | "successfully saved EE curationTasks (2)"

        GENE_VARIANT_DATA_ELEMENT | [bCurationTaskSaveSuccess: false, savedCurationIds: [], statusMessage: "Error saving curation tasks."]       | 'Error processing ' + GENE_VARIANT_DATA_ELEMENT + ' maintenance tasks : Error saving curation tasks.'                              | "failed (actionable) gene variant curationTasks"
        EE_DATA_ELEMENT           | [bCurationTaskSaveSuccess: false, savedCurationIds: [], statusMessage: "Error saving curation tasks."]       | 'Error processing ' + EE_DATA_ELEMENT + ' maintenance tasks : Error saving curation tasks.'                                        | "failed EE curationTasks"

    }

    void "test createJobLogMap #desc"() {
        when:
        def actualMap = service.createJobLogMap(dataElement, taskType, curationTasksMap)

        then:
        actualMap == expectedMap

        where:
        dataElement               | taskType                                | curationTasksMap                                                                                             | expectedMap                                                                                                                                                                                                                  | desc
        GENE_VARIANT_DATA_ELEMENT | TASK_TYPE_MAINTENANCE                   | [bCurationTaskSaveSuccess: true, savedCurationIds: ['BRAF V600E', 'HRAS G12S'], statusMessage: null]         | [name: 'Actionable Gene Variants Maintenance', message: 'Successfully added 2 ' + GENE_VARIANT_DATA_ELEMENT + ' maintenance curation tasks to curation workflow : [BRAF V600E, HRAS G12S]', status: JOB_LOG_STATUS_SUCCESS]  | "successfully saved (actionable) gene variant curationTasks (2)"

        GENE_VARIANT_DATA_ELEMENT | TASK_TYPE_MAINTENANCE_NON_ACTIONABLE_GV | [bCurationTaskSaveSuccess: true, savedCurationIds: ['GATA1 E2K', 'CIC M9V'], statusMessage: null]            | [name: 'Non-actionable Gene Variants Maintenance', message: 'Successfully added 2 ' + GENE_VARIANT_DATA_ELEMENT + ' maintenance curation tasks to curation workflow : [GATA1 E2K, CIC M9V]', status: JOB_LOG_STATUS_SUCCESS] | "successfully saved (non-actinable) gene variant curationTasks (2)"

        EE_DATA_ELEMENT           | TASK_TYPE_MAINTENANCE                   | [bCurationTaskSaveSuccess: true, savedCurationIds: ['BRAF V600E 123', 'HRAS G12S 321'], statusMessage: null] | [name: 'Efficacy Evidence Maintenance', message: 'Successfully added 2 ' + EE_DATA_ELEMENT + ' maintenance curation tasks to curation workflow : [BRAF V600E 123, HRAS G12S 321]', status: JOB_LOG_STATUS_SUCCESS]           | "successfully saved EE curationTasks (2)"

        GENE_VARIANT_DATA_ELEMENT | TASK_TYPE_MAINTENANCE                   | [bCurationTaskSaveSuccess: false, savedCurationIds: [], statusMessage: "Error saving curation tasks."]       | [name: 'Actionable Gene Variants Maintenance', message: 'Error processing ' + GENE_VARIANT_DATA_ELEMENT + ' maintenance tasks : Error saving curation tasks.', status: JOB_LOG_STATUS_FAILED]                                | "failed (actionable) gene variant curationTasks"
        GENE_VARIANT_DATA_ELEMENT | TASK_TYPE_MAINTENANCE_NON_ACTIONABLE_GV | [bCurationTaskSaveSuccess: false, savedCurationIds: [], statusMessage: "Error saving curation tasks."]       | [name: 'Non-actionable Gene Variants Maintenance', message: 'Error processing ' + GENE_VARIANT_DATA_ELEMENT + ' maintenance tasks : Error saving curation tasks.', status: JOB_LOG_STATUS_FAILED]                            | "failed (non-actionable) gene variant curationTasks"
        EE_DATA_ELEMENT           | TASK_TYPE_MAINTENANCE                   | [bCurationTaskSaveSuccess: false, savedCurationIds: [], statusMessage: "Error saving curation tasks."]       | [name: 'Efficacy Evidence Maintenance', message: 'Error processing ' + EE_DATA_ELEMENT + ' maintenance tasks : Error saving curation tasks.', status: JOB_LOG_STATUS_FAILED]                                                 | "failed EE curationTasks"
    }


}
