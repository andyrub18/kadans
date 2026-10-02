using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Kadans.Modules.Tasks.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class PomodoroTimeUp : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<int>(
                name: "time_up_phase_index",
                schema: "tasks",
                table: "pomodoro_runs",
                type: "integer",
                nullable: true);

            migrationBuilder.CreateIndex(
                name: "ix_pomodoro_runs_manual_due",
                schema: "tasks",
                table: "pomodoro_runs",
                column: "phase_ends_at",
                filter: "status = 'Active' AND NOT auto_advance");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropIndex(
                name: "ix_pomodoro_runs_manual_due",
                schema: "tasks",
                table: "pomodoro_runs");

            migrationBuilder.DropColumn(
                name: "time_up_phase_index",
                schema: "tasks",
                table: "pomodoro_runs");
        }
    }
}
