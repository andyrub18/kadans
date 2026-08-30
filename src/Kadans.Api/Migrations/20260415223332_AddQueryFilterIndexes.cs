using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Kadans.Api.Migrations
{
    /// <inheritdoc />
    public partial class AddQueryFilterIndexes : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropIndex(
                name: "IX_todos_user_id",
                table: "todos");

            migrationBuilder.DropIndex(
                name: "IX_todo_occurrences_todo_id",
                table: "todo_occurrences");

            migrationBuilder.CreateIndex(
                name: "ix_todos_user_id_created_at_desc",
                table: "todos",
                columns: new[] { "user_id", "created_at" },
                descending: new[] { false, true });

            migrationBuilder.CreateIndex(
                name: "ix_todos_user_id_created_at_id_active",
                table: "todos",
                columns: new[] { "user_id", "created_at", "id" },
                descending: new[] { false, true, false },
                filter: "status IN ('Scheduled', 'Started')");

            migrationBuilder.CreateIndex(
                name: "ix_todos_user_id_id",
                table: "todos",
                columns: new[] { "user_id", "id" });

            migrationBuilder.CreateIndex(
                name: "ix_todos_user_id_status_created_at_desc",
                table: "todos",
                columns: new[] { "user_id", "status", "created_at" },
                descending: new[] { false, false, true });

            migrationBuilder.CreateIndex(
                name: "ix_todo_occurrences_occurrence_date_active",
                table: "todo_occurrences",
                column: "occurrence_date",
                filter: "NOT is_cancelled AND NOT is_completed");

            migrationBuilder.CreateIndex(
                name: "ix_todo_occurrences_todo_id_occurrence_date",
                table: "todo_occurrences",
                columns: new[] { "todo_id", "occurrence_date" });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropIndex(
                name: "ix_todos_user_id_created_at_desc",
                table: "todos");

            migrationBuilder.DropIndex(
                name: "ix_todos_user_id_created_at_id_active",
                table: "todos");

            migrationBuilder.DropIndex(
                name: "ix_todos_user_id_id",
                table: "todos");

            migrationBuilder.DropIndex(
                name: "ix_todos_user_id_status_created_at_desc",
                table: "todos");

            migrationBuilder.DropIndex(
                name: "ix_todo_occurrences_occurrence_date_active",
                table: "todo_occurrences");

            migrationBuilder.DropIndex(
                name: "ix_todo_occurrences_todo_id_occurrence_date",
                table: "todo_occurrences");

            migrationBuilder.CreateIndex(
                name: "IX_todos_user_id",
                table: "todos",
                column: "user_id");

            migrationBuilder.CreateIndex(
                name: "IX_todo_occurrences_todo_id",
                table: "todo_occurrences",
                column: "todo_id");
        }
    }
}
