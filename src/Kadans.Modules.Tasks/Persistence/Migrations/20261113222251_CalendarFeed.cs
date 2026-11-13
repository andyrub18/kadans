using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Kadans.Modules.Tasks.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class CalendarFeed : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "calendar_feed_links",
                schema: "tasks",
                columns: table => new
                {
                    user_id = table.Column<string>(type: "character varying(450)", maxLength: 450, nullable: false),
                    nonce = table.Column<byte[]>(type: "bytea", maxLength: 16, nullable: false),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    last_fetched_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_calendar_feed_links", x => x.user_id);
                });

            migrationBuilder.CreateIndex(
                name: "ix_calendar_feed_links_nonce",
                schema: "tasks",
                table: "calendar_feed_links",
                column: "nonce",
                unique: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "calendar_feed_links",
                schema: "tasks");
        }
    }
}
