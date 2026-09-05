using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Kadans.Modules.Tasks.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class PomodoroLoop : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<int>(
                name: "cycle_length",
                schema: "tasks",
                table: "pomodoro_runs",
                type: "integer",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<bool>(
                name: "loop",
                schema: "tasks",
                table: "pomodoro_runs",
                type: "boolean",
                nullable: false,
                defaultValue: false);

            // Existing runs: one lap is exactly the phases they already have.
            migrationBuilder.Sql("""
                UPDATE tasks.pomodoro_runs r SET cycle_length =
                    (SELECT COUNT(*) FROM tasks.pomodoro_run_phases p WHERE p.pomodoro_run_id = r.id);
                """);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "cycle_length",
                schema: "tasks",
                table: "pomodoro_runs");

            migrationBuilder.DropColumn(
                name: "loop",
                schema: "tasks",
                table: "pomodoro_runs");
        }
    }
}
