package sqlancer.postgres;

import java.io.FileWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.beust.jcommander.JCommander;

import sqlancer.AbstractAction;
import sqlancer.CompositeTestOracle;
import sqlancer.DatabaseProvider;
import sqlancer.IgnoreMeException;
import sqlancer.Main.QueryManager;
import sqlancer.Main.StateLogger;
import sqlancer.MainOptions;
import sqlancer.Query;
import sqlancer.QueryAdapter;
import sqlancer.Randomly;
import sqlancer.StateToReproduce;
import sqlancer.StateToReproduce.PostgresStateToReproduce;
import sqlancer.StatementExecutor;
import sqlancer.TestOracle;
import sqlancer.postgres.PostgresSchema.PostgresColumn;
import sqlancer.postgres.PostgresSchema.PostgresTable;
import sqlancer.postgres.ast.PostgresExpression;
import sqlancer.postgres.gen.PostgresAlterTableGenerator;
import sqlancer.postgres.gen.PostgresAnalyzeGenerator;
import sqlancer.postgres.gen.PostgresClusterGenerator;
import sqlancer.postgres.gen.PostgresCommentGenerator;
import sqlancer.postgres.gen.PostgresDeleteGenerator;
import sqlancer.postgres.gen.PostgresDiscardGenerator;
import sqlancer.postgres.gen.PostgresDropIndex;
import sqlancer.postgres.gen.PostgresIndexGenerator;
import sqlancer.postgres.gen.PostgresInsertGenerator;
import sqlancer.postgres.gen.PostgresNotifyGenerator;
import sqlancer.postgres.gen.PostgresQueryCatalogGenerator;
import sqlancer.postgres.gen.PostgresReindexGenerator;
import sqlancer.postgres.gen.PostgresSequenceGenerator;
import sqlancer.postgres.gen.PostgresSetGenerator;
import sqlancer.postgres.gen.PostgresStatisticsGenerator;
import sqlancer.postgres.gen.PostgresTableGenerator;
import sqlancer.postgres.gen.PostgresTransactionGenerator;
import sqlancer.postgres.gen.PostgresTruncateGenerator;
import sqlancer.postgres.gen.PostgresUpdateGenerator;
import sqlancer.postgres.gen.PostgresVacuumGenerator;
import sqlancer.postgres.gen.PostgresViewGenerator;
import sqlancer.sqlite3.gen.SQLite3Common;

// EXISTS
// IN
public class PostgresProvider implements DatabaseProvider<PostgresGlobalState> {

	public static boolean GENERATE_ONLY_KNOWN = false;

	private PostgresGlobalState globalState;

	@FunctionalInterface
	public interface PostgresQueryProvider {

		Query getQuery(PostgresGlobalState globalState) throws SQLException;
	}

	public enum Action implements AbstractAction<PostgresGlobalState> {
		ANALYZE(PostgresAnalyzeGenerator::create), //
		ALTER_TABLE(g -> PostgresAlterTableGenerator.create(g.getSchema().getRandomTable(t -> !t.isView()),
				g.getRandomly(), g.getSchema(), GENERATE_ONLY_KNOWN, g.getOpClasses(), g.getOperators())), //
		CLUSTER(PostgresClusterGenerator::create), //
		COMMIT(g -> {
			Query query;
			if (Randomly.getBoolean()) {
				query = new QueryAdapter("COMMIT") {
					@Override
					public boolean couldAffectSchema() {
						return true;
					}
				};
			} else if (Randomly.getBoolean()) {
				query = PostgresTransactionGenerator.executeBegin();
			} else {
				query = new QueryAdapter("ROLLBACK") {
					@Override
					public boolean couldAffectSchema() {
						return true;
					}
				};
			}
			return query;
		}), //
		CREATE_STATISTICS(PostgresStatisticsGenerator::insert), //
		DROP_STATISTICS(PostgresStatisticsGenerator::remove), //
		DELETE(PostgresDeleteGenerator::create), //
		DISCARD(PostgresDiscardGenerator::create), //
		DROP_INDEX(PostgresDropIndex::create), //
		INSERT(PostgresInsertGenerator::insert), //
		UPDATE(PostgresUpdateGenerator::create), //
		TRUNCATE(PostgresTruncateGenerator::create), //
		VACUUM(PostgresVacuumGenerator::create), //
		REINDEX(PostgresReindexGenerator::create), //
		SET(PostgresSetGenerator::create), //
		CREATE_INDEX(PostgresIndexGenerator::generate), //
		SET_CONSTRAINTS((g) -> {
			StringBuilder sb = new StringBuilder();
			sb.append("SET CONSTRAINTS ALL ");
			sb.append(Randomly.fromOptions("DEFERRED", "IMMEDIATE"));
			return new QueryAdapter(sb.toString());
		}), //
		RESET_ROLE((g) -> new QueryAdapter("RESET ROLE")), //
		COMMENT_ON(PostgresCommentGenerator::generate), //
		RESET((g) -> new QueryAdapter("RESET ALL") /*
													 * https://www.postgresql.org/docs/devel/sql-reset.html TODO: also
													 * configuration parameter
													 */), //
		NOTIFY(PostgresNotifyGenerator::createNotify), //
		LISTEN((g) -> PostgresNotifyGenerator.createListen()), //
		UNLISTEN((g) -> PostgresNotifyGenerator.createUnlisten()), //
		CREATE_SEQUENCE(PostgresSequenceGenerator::createSequence), //
		CREATE_VIEW(PostgresViewGenerator::create), //
		QUERY_CATALOG((g) -> PostgresQueryCatalogGenerator.query());

		private final PostgresQueryProvider queryProvider;

		private Action(PostgresQueryProvider queryProvider) {
			this.queryProvider = queryProvider;
		}

		public Query getQuery(PostgresGlobalState state) throws SQLException {
			return queryProvider.getQuery(state);
		}
	}

	private static int mapActions(PostgresGlobalState globalState, Action a) {
		Randomly r = globalState.getRandomly();
		int nrPerformed;
		switch (a) {
		case CREATE_INDEX:
			nrPerformed = r.getInteger(0, 10);
			break;
		case CREATE_STATISTICS:
			nrPerformed = r.getInteger(0, 5);
			break;
		case DISCARD:
		case DROP_INDEX:
			nrPerformed = r.getInteger(0, 5);
			break;
		case COMMIT:
		case CLUSTER: // FIXME cluster broke during refactoring
			nrPerformed = r.getInteger(0, 0);
			break;
		case ALTER_TABLE:
			nrPerformed = r.getInteger(0, 5);
			break;
		case REINDEX:
		case RESET:
			nrPerformed = r.getInteger(0, 5);
			break;
		case DELETE:
		case RESET_ROLE:
		case SET:
		case QUERY_CATALOG:
			nrPerformed = r.getInteger(0, 5);
			break;
		case ANALYZE:
			nrPerformed = r.getInteger(0, 10);
			break;
		case VACUUM:
		case SET_CONSTRAINTS:
		case COMMENT_ON:
		case NOTIFY:
		case LISTEN:
		case UNLISTEN:
		case CREATE_SEQUENCE:
		case DROP_STATISTICS:
		case TRUNCATE:
			nrPerformed = r.getInteger(0, 2);
			break;
		case CREATE_VIEW:
			nrPerformed = r.getInteger(0, 2);
			break;
		case UPDATE:
			nrPerformed = r.getInteger(0, 10);
			break;
		case INSERT:
			nrPerformed = r.getInteger(0, 30);
			break;
		default:
			throw new AssertionError(a);
		}
		return nrPerformed;

	}

	@Override
	public void generateAndTestDatabase(PostgresGlobalState globalState) throws SQLException {
		MainOptions options = globalState.getOptions();
		StateLogger logger = globalState.getLogger();
		StateToReproduce state = globalState.getState();
		String databaseName = globalState.getDatabaseName();
		Connection con = globalState.getConnection();
		QueryManager manager = globalState.getManager();
		PostgresOptions PostgresOptions = new PostgresOptions();
		JCommander.newBuilder().addObject(PostgresOptions).build().parse(globalState.getOptions().getDbmsOptions().split(" "));
		globalState.setPostgresOptions(PostgresOptions);
		if (options.logEachSelect()) {
			logger.writeCurrent(state);
		}
		globalState.setSchema(PostgresSchema.fromConnection(con, databaseName));
		while (globalState.getSchema().getDatabaseTables().size() < 2) {
			try {
				String tableName = SQLite3Common.createTableName(globalState.getSchema().getDatabaseTables().size());
				Query createTable = PostgresTableGenerator.generate(tableName, globalState.getRandomly(), globalState.getSchema(),
						GENERATE_ONLY_KNOWN, globalState);
				if (options.logEachSelect()) {
					logger.writeCurrent(createTable.getQueryString());
				}
				manager.execute(createTable);
				globalState.setSchema(PostgresSchema.fromConnection(con, databaseName));
			} catch (IgnoreMeException e) {

			}
		}

		StatementExecutor<PostgresGlobalState, Action> se = new StatementExecutor<PostgresGlobalState, Action>(
				globalState, globalState.getDatabaseName(), Action.values(), PostgresProvider::mapActions, (q) -> {
					if (q.couldAffectSchema()) {
						globalState.setSchema(PostgresSchema.fromConnection(con, databaseName));
					}
					if (globalState.getSchema().getDatabaseTables().isEmpty()) {
						throw new IgnoreMeException();
					}
				});
		// TODO: transactions broke during refactoring
//		catch (Throwable t) {
//			if (t.getMessage().contains("current transaction is aborted")) {
//				manager.execute(new QueryAdapter("ABORT"));
//				globalState.setSchema(PostgresSchema.fromConnection(con, databaseName));
//			} else {
//				System.err.println(query.getQueryString());
//				throw t;
//			}
//		}
		se.executeStatements();
		manager.incrementCreateDatabase();
		manager.execute(new QueryAdapter("COMMIT"));
		globalState.setSchema(PostgresSchema.fromConnection(con, databaseName));

		manager.execute(new QueryAdapter("SET SESSION statement_timeout = 5000;\n"));

		
		List<TestOracle> oracles = globalState.getPostgresOptions().oracle.stream().map(o -> {
			try {
				return o.create(globalState);
			} catch (SQLException e1) {
				throw new AssertionError(e1);
			}
		}).collect(Collectors.toList());
		CompositeTestOracle oracle = new CompositeTestOracle(oracles);
		
		for (int i = 0; i < options.getNrQueries(); i++) {
			try {
				oracle.check();
			} catch (IgnoreMeException e) {
				continue;
			}
			manager.incrementSelectQueryCount();
		}

	}

	public static int getNrRows(Connection con, PostgresTable table) throws SQLException {
		try (Statement s = con.createStatement()) {
			try (ResultSet query = s.executeQuery("SELECT COUNT(*) FROM " + table.getName())) {
				query.next();
				return query.getInt(1);
			}
		}
	}

	@Override
	public Connection createDatabase(String databaseName, StateToReproduce state) throws SQLException {
		String url = "jdbc:postgresql://localhost:5432/test";
		Connection con = DriverManager.getConnection(url, "lama", "password");
		state.statements.add(new QueryAdapter("\\c test;"));
		state.statements.add(new QueryAdapter("DROP DATABASE IF EXISTS " + databaseName));
		String createDatabaseCommand = getCreateDatabaseCommand(databaseName, con);
		state.statements.add(new QueryAdapter(createDatabaseCommand));
		state.statements.add(new QueryAdapter("\\c " + databaseName));
		try (Statement s = con.createStatement()) {
			s.execute("DROP DATABASE IF EXISTS " + databaseName);
		}
		try (Statement s = con.createStatement()) {
			s.execute(createDatabaseCommand);
		}
		con.close();
		con = DriverManager.getConnection("jdbc:postgresql://localhost:5432/" + databaseName, "lama", "password");
		List<String> statements = Arrays.asList(
//				"CREATE EXTENSION IF NOT EXISTS btree_gin;",
//				"CREATE EXTENSION IF NOT EXISTS btree_gist;", // TODO:  undefined symbol: elog_start
				"CREATE EXTENSION IF NOT EXISTS pg_prewarm;", "SET max_parallel_workers_per_gather=16");
		for (String s : statements) {
			QueryAdapter query = new QueryAdapter(s);
			state.statements.add(query);
			query.execute(con);
		}
//		new QueryAdapter("set jit_above_cost = 0; set jit_inline_above_cost = 0; set jit_optimize_above_cost = 0;").execute(con);
		return con;
	}

	private String getCreateDatabaseCommand(String databaseName, Connection con) {
		StringBuilder sb = new StringBuilder();
		sb.append("CREATE DATABASE " + databaseName + " ");
		if (Randomly.getBoolean()) {
			if (Randomly.getBoolean()) {
				sb.append("WITH ENCODING '");
				sb.append(Randomly.fromOptions("utf8"));
				sb.append("' ");
			}
			for (String lc : Arrays.asList("LC_COLLATE", "LC_CTYPE")) {
				if (Randomly.getBoolean()) {
					globalState = new PostgresGlobalState();
					globalState.setConnection(con);
					sb.append(String.format(" %s = '%s'", lc, Randomly.fromList(globalState.getCollates())));
				}
			}
			sb.append(" TEMPLATE template0");
		}
		return sb.toString();
	}

	@Override
	public String getLogFileSubdirectoryName() {
		return "postgres";
	}

	@Override
	public void printDatabaseSpecificState(FileWriter writer, StateToReproduce state) {
		StringBuilder sb = new StringBuilder();
		PostgresStateToReproduce specificState = (PostgresStateToReproduce) state;
		if (specificState.getRandomRowValues() != null) {
			List<PostgresColumn> columnList = specificState.getRandomRowValues().keySet().stream()
					.collect(Collectors.toList());
			List<PostgresTable> tableList = columnList.stream().map(c -> c.getTable()).distinct().sorted()
					.collect(Collectors.toList());
			for (PostgresTable t : tableList) {
				sb.append("-- " + t.getName() + "\n");
				List<PostgresColumn> columnsForTable = columnList.stream().filter(c -> c.getTable().equals(t))
						.collect(Collectors.toList());
				for (PostgresColumn c : columnsForTable) {
					sb.append("--\t");
					sb.append(c);
					sb.append("=");
					sb.append(specificState.getRandomRowValues().get(c));
					sb.append("\n");
				}
			}
			sb.append("expected values: \n");
			PostgresExpression whereClause = ((PostgresStateToReproduce) state).getWhereClause();
			if (whereClause != null) {
				sb.append(PostgresVisitor.asExpectedValues(whereClause).replace("\n", "\n-- "));
			}
		}
		try {
			writer.write(sb.toString());
			writer.flush();
		} catch (IOException e) {
			throw new AssertionError();
		}
	}

	@Override
	public StateToReproduce getStateToReproduce(String databaseName) {
		return new PostgresStateToReproduce(databaseName);
	}

	@Override
	public PostgresGlobalState generateGlobalState() {
		return new PostgresGlobalState();
	}

}