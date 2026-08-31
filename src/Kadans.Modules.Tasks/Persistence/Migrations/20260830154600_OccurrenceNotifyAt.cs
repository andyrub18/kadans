using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Kadans.Modules.Tasks.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class OccurrenceNotifyAt : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<DateTimeOffset>(
                name: "notify_at",
                schema: "tasks",
                table: "todo_occurrences",
                type: "timestamp with time zone",
                nullable: true);

            // Existing pending rows get their reminder time; without this only new occurrences would remind.
            migrationBuilder.Sql("""
                UPDATE tasks.todo_occurrences o
                SET notify_at = o.scheduled_at - t.notification_lead_time
                FROM tasks.todos t
                WHERE t.id = o.todo_id AND t.notification_enabled AND o.status = 'Pending';
                """);

            migrationBuilder.CreateIndex(
                name: "ix_todo_occurrences_notify_due",
                schema: "tasks",
                table: "todo_occurrences",
                column: "notify_at",
                filter: "status = 'Pending' AND notified_at IS NULL AND notify_at IS NOT NULL");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropIndex(
                name: "ix_todo_occurrences_notify_due",
                schema: "tasks",
                table: "todo_occurrences");

            migrationBuilder.DropColumn(
                name: "notify_at",
                schema: "tasks",
                table: "todo_occurrences");
        }
    }
}
