using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Kadans.Modules.Tasks.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class OccurrenceOverrides : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            // Hand-ordered: the scaffold dropped the boolean flags before the status column existed
            // and could not backfill original_scheduled_at. Existing rows are migrated, not lost.
            migrationBuilder.DropIndex(name: "ix_todo_occurrences_occurrence_date_active", schema: "tasks", table: "todo_occurrences");
            migrationBuilder.DropIndex(name: "ix_todo_occurrences_todo_id_occurrence_date", schema: "tasks", table: "todo_occurrences");

            migrationBuilder.RenameColumn(name: "occurrence_date", schema: "tasks", table: "todo_occurrences", newName: "scheduled_at");

            migrationBuilder.AddColumn<DateTimeOffset>(name: "original_scheduled_at", schema: "tasks", table: "todo_occurrences", type: "timestamp with time zone", nullable: true);
            migrationBuilder.AddColumn<string>(name: "status", schema: "tasks", table: "todo_occurrences", type: "text", nullable: false, defaultValue: "Pending");
            migrationBuilder.AddColumn<DateTimeOffset>(name: "cancelled_at", schema: "tasks", table: "todo_occurrences", type: "timestamp with time zone", nullable: true);
            migrationBuilder.AddColumn<DateTimeOffset>(name: "rescheduled_at", schema: "tasks", table: "todo_occurrences", type: "timestamp with time zone", nullable: true);
            migrationBuilder.AddColumn<string>(name: "reschedule_reason", schema: "tasks", table: "todo_occurrences", type: "character varying(4000)", maxLength: 4000, nullable: true);
            migrationBuilder.AddColumn<DateTimeOffset>(name: "notified_at", schema: "tasks", table: "todo_occurrences", type: "timestamp with time zone", nullable: true);
            migrationBuilder.AddColumn<DateTimeOffset>(name: "occurrences_generated_through", schema: "tasks", table: "todos", type: "timestamp with time zone", nullable: true);

            migrationBuilder.AlterColumn<string>(name: "remarks", schema: "tasks", table: "todo_occurrences", type: "character varying(4000)", maxLength: 4000, nullable: true, oldClrType: typeof(string), oldType: "character varying(4000)", oldMaxLength: 4000);
            migrationBuilder.AlterColumn<string>(name: "cancellation_reason", schema: "tasks", table: "todo_occurrences", type: "character varying(4000)", maxLength: 4000, nullable: true, oldClrType: typeof(string), oldType: "character varying(4000)", oldMaxLength: 4000);

            migrationBuilder.Sql("""
                UPDATE tasks.todo_occurrences SET
                    original_scheduled_at = scheduled_at,
                    status = CASE WHEN is_completed THEN 'Completed' WHEN is_cancelled THEN 'Cancelled' ELSE 'Pending' END,
                    cancelled_at = CASE WHEN is_cancelled THEN scheduled_at END,
                    rescheduled_at = CASE WHEN is_rescheduled THEN scheduled_at END,
                    remarks = NULLIF(remarks, ''),
                    cancellation_reason = NULLIF(cancellation_reason, '');
                """);

            migrationBuilder.AlterColumn<DateTimeOffset>(name: "original_scheduled_at", schema: "tasks", table: "todo_occurrences", type: "timestamp with time zone", nullable: false, oldClrType: typeof(DateTimeOffset), oldType: "timestamp with time zone", oldNullable: true);

            migrationBuilder.DropColumn(name: "is_cancelled", schema: "tasks", table: "todo_occurrences");
            migrationBuilder.DropColumn(name: "is_completed", schema: "tasks", table: "todo_occurrences");
            migrationBuilder.DropColumn(name: "is_rescheduled", schema: "tasks", table: "todo_occurrences");

            migrationBuilder.CreateIndex(name: "ix_todos_generated_through_active", schema: "tasks", table: "todos", column: "occurrences_generated_through", filter: "status IN ('Scheduled', 'Started')");
            migrationBuilder.CreateIndex(name: "ix_todo_occurrences_scheduled_at_pending", schema: "tasks", table: "todo_occurrences", column: "scheduled_at", filter: "status = 'Pending'");
            migrationBuilder.CreateIndex(name: "ix_todo_occurrences_todo_id_original_scheduled_at", schema: "tasks", table: "todo_occurrences", columns: new[] { "todo_id", "original_scheduled_at" }, unique: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropIndex(
                name: "ix_todos_generated_through_active",
                schema: "tasks",
                table: "todos");

            migrationBuilder.DropIndex(
                name: "ix_todo_occurrences_scheduled_at_pending",
                schema: "tasks",
                table: "todo_occurrences");

            migrationBuilder.DropIndex(
                name: "ix_todo_occurrences_todo_id_original_scheduled_at",
                schema: "tasks",
                table: "todo_occurrences");

            migrationBuilder.DropColumn(
                name: "occurrences_generated_through",
                schema: "tasks",
                table: "todos");

            migrationBuilder.DropColumn(
                name: "cancelled_at",
                schema: "tasks",
                table: "todo_occurrences");

            migrationBuilder.DropColumn(
                name: "notified_at",
                schema: "tasks",
                table: "todo_occurrences");

            migrationBuilder.DropColumn(
                name: "original_scheduled_at",
                schema: "tasks",
                table: "todo_occurrences");

            migrationBuilder.DropColumn(
                name: "reschedule_reason",
                schema: "tasks",
                table: "todo_occurrences");

            migrationBuilder.DropColumn(
                name: "rescheduled_at",
                schema: "tasks",
                table: "todo_occurrences");

            migrationBuilder.DropColumn(
                name: "status",
                schema: "tasks",
                table: "todo_occurrences");

            migrationBuilder.RenameColumn(
                name: "scheduled_at",
                schema: "tasks",
                table: "todo_occurrences",
                newName: "occurrence_date");

            migrationBuilder.AlterColumn<string>(
                name: "remarks",
                schema: "tasks",
                table: "todo_occurrences",
                type: "character varying(4000)",
                maxLength: 4000,
                nullable: false,
                defaultValue: "",
                oldClrType: typeof(string),
                oldType: "character varying(4000)",
                oldMaxLength: 4000,
                oldNullable: true);

            migrationBuilder.AlterColumn<string>(
                name: "cancellation_reason",
                schema: "tasks",
                table: "todo_occurrences",
                type: "character varying(4000)",
                maxLength: 4000,
                nullable: false,
                defaultValue: "",
                oldClrType: typeof(string),
                oldType: "character varying(4000)",
                oldMaxLength: 4000,
                oldNullable: true);

            migrationBuilder.AddColumn<bool>(
                name: "is_cancelled",
                schema: "tasks",
                table: "todo_occurrences",
                type: "boolean",
                nullable: false,
                defaultValue: false);

            migrationBuilder.AddColumn<bool>(
                name: "is_completed",
                schema: "tasks",
                table: "todo_occurrences",
                type: "boolean",
                nullable: false,
                defaultValue: false);

            migrationBuilder.AddColumn<bool>(
                name: "is_rescheduled",
                schema: "tasks",
                table: "todo_occurrences",
                type: "boolean",
                nullable: false,
                defaultValue: false);

            migrationBuilder.CreateIndex(
                name: "ix_todo_occurrences_occurrence_date_active",
                schema: "tasks",
                table: "todo_occurrences",
                column: "occurrence_date",
                filter: "NOT is_cancelled AND NOT is_completed");

            migrationBuilder.CreateIndex(
                name: "ix_todo_occurrences_todo_id_occurrence_date",
                schema: "tasks",
                table: "todo_occurrences",
                columns: new[] { "todo_id", "occurrence_date" });
        }
    }
}
