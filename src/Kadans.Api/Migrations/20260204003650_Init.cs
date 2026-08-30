using System;
using System.Collections.Generic;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Kadans.Api.Migrations
{
    /// <inheritdoc />
    public partial class Init : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "recurrence_rules",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    frequency = table.Column<string>(type: "text", nullable: false),
                    interval = table.Column<int>(type: "integer", nullable: false),
                    start_date = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    by_hour = table.Column<List<int>>(type: "integer[]", nullable: true),
                    by_minute = table.Column<List<int>>(type: "integer[]", nullable: true),
                    by_day = table.Column<int[]>(type: "integer[]", nullable: true),
                    by_month_day = table.Column<List<int>>(type: "integer[]", nullable: true),
                    by_month = table.Column<List<int>>(type: "integer[]", nullable: true),
                    by_set_pos = table.Column<List<int>>(type: "integer[]", nullable: true),
                    until = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                    count = table.Column<int>(type: "integer", nullable: true),
                    exceptions = table.Column<List<DateTimeOffset>>(type: "timestamp with time zone[]", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_recurrence_rules", x => x.id);
                });

            migrationBuilder.CreateTable(
                name: "todos",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    title = table.Column<string>(type: "character varying(500)", maxLength: 500, nullable: false),
                    description = table.Column<string>(type: "character varying(4000)", maxLength: 4000, nullable: false),
                    recurrence_rule_id = table.Column<Guid>(type: "uuid", nullable: false),
                    notification_enabled = table.Column<bool>(type: "boolean", nullable: false),
                    status = table.Column<string>(type: "text", nullable: false),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_todos", x => x.id);
                    table.ForeignKey(
                        name: "fk_todos_recurrence_rules_recurrence_rule_id",
                        column: x => x.recurrence_rule_id,
                        principalTable: "recurrence_rules",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "todo_occurrences",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    todo_id = table.Column<Guid>(type: "uuid", nullable: false),
                    occurrence_date = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    remarks = table.Column<string>(type: "character varying(4000)", maxLength: 4000, nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_todo_occurrences", x => x.id);
                    table.ForeignKey(
                        name: "fk_todo_occurrences_todos_todo_id",
                        column: x => x.todo_id,
                        principalTable: "todos",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "todo_remarks",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    remark = table.Column<string>(type: "character varying(2000)", maxLength: 2000, nullable: false),
                    TodoId = table.Column<Guid>(type: "uuid", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_todo_remarks", x => x.Id);
                    table.ForeignKey(
                        name: "fk_todo_remark_todos_todo_id",
                        column: x => x.TodoId,
                        principalTable: "todos",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "IX_todo_occurrences_todo_id",
                table: "todo_occurrences",
                column: "todo_id");

            migrationBuilder.CreateIndex(
                name: "IX_todo_remarks_TodoId",
                table: "todo_remarks",
                column: "TodoId");

            migrationBuilder.CreateIndex(
                name: "IX_todos_recurrence_rule_id",
                table: "todos",
                column: "recurrence_rule_id");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "todo_occurrences");

            migrationBuilder.DropTable(
                name: "todo_remarks");

            migrationBuilder.DropTable(
                name: "todos");

            migrationBuilder.DropTable(
                name: "recurrence_rules");
        }
    }
}
