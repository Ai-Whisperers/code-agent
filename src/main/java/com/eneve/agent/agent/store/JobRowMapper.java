package com.eneve.agent.agent.store;

import com.eneve.agent.model.JobRecord;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Maps a JDBC {@link ResultSet} row to a {@link JobRecord}.
 * Implemented by {@link JobStore} and shared with {@link JobQueryHelper}.
 */
@FunctionalInterface
interface JobRowMapper {
    JobRecord mapRow(ResultSet rs) throws SQLException;
}
