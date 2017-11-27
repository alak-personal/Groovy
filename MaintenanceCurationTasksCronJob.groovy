package ckb.utilities

import org.jax.ckb.CurationTaskService
import org.jax.ckb.JobLogService


class MaintenanceCurationTasksCronJob {
    CurationTaskService curationTaskService

    static triggers = {
        // Fire everyday at 1:00am
        cron name: 'maintenanceCurationTask', cronExpression: "0 0 1 * * ?"
    }

    def concurrent = false

    def execute() {
        // execute job
        log.error("Maintenance curation Job  started at : " + new Date())
        try {
            curationTaskService.runMaintenanceCurationTasksCronJob()
        } catch (Exception e) {
            log.error("Error completing job : " + e.getMessage())
        }
        log.error("Maintenance curation Job completed at : " + new Date())
    }
}
